/*
 * ExclusionTimes – ScriptRunner for Jira Cloud
 * ------------------------------------------------------------
 * Type      : Script Listener
 * Events    : Issue Created, Issue Updated
 * Projects  : <the Change project(s)>
 * Run as    : ScriptRunner Add-on User
 *
 * Script Variables (ScriptRunner > Settings > Script Variables):
 *   ASSETS_USER_EMAIL  – service account email
 *   ASSETS_API_TOKEN   – service account API token (secret)
 *   ASSETS_CLOUD_ID    – site cloudId (required for service-account tokens;
 *                        omit only if using a regular user's token)
 *
 * Errors are collected and re-thrown at the end, so ScriptRunner's
 * failure notification email is sent (Settings > Notifications).
 *
 * Email to shintashtit@... is NOT sent from here (no Mail API in Cloud) –
 * use the Automation rule described at the bottom.
 */

import java.time.*
import java.time.format.DateTimeFormatter

// ===================== CONFIG =====================
def FIELD_START   = "תאריך תחילת עבודה"
def FIELD_END     = "תאריך סיום עבודה"
def FIELD_SERVERS = "Servers"
// If a field name is not unique in Cloud, hardcode its ID here
def FIELD_ID_OVERRIDES = [:]   // e.g. [ (FIELD_SERVERS): "customfield_10123" ]

def SCHEMA     = "IT Operations"
def EXCL_TYPE  = "Exclusion"
def ATTR_START = "start_date"   // Cloud attribute names (DC was "Start Date"/"End Date")
def ATTR_END   = "end_date"

// Transition IDs change after migration – verify them in the Cloud workflow!
//def TRANSITION_CHANGE     = "31"
//def TRANSITION_SUB_CHANGE = "121"

def TRANSITION_CHANGE     = "3"
def TRANSITION_SUB_CHANGE = "3"

def TZ = ZoneId.of("Asia/Jerusalem")
// ==================================================

// ---------- read Script Variables ----------
def scriptVar = { String name, boolean required ->
    def v = binding.hasVariable(name) ? binding.getVariable(name) : null
    if (required && !v) throw new IllegalStateException("Script Variable '${name}' is missing")
    v as String
}
String assetsEmail = scriptVar("ASSETS_USER_EMAIL", true)
String assetsToken = scriptVar("ASSETS_API_TOKEN", true)
String cloudId     = scriptVar("ASSETS_CLOUD_ID", false)

def issueKey = issue.key as String
List<String> errors = []
logger.info("=== ExclusionTimes start: ${issueKey} | event=${binding.variables.webhookEvent} | cloudId set=${cloudId as boolean} ===")

// ---------- resolve custom field IDs by name ----------
def allFields = get("/rest/api/3/field").asObject(List).body as List<Map>
def fieldId = { String name ->
    if (FIELD_ID_OVERRIDES[name]) return FIELD_ID_OVERRIDES[name] as String
    def matches = allFields.findAll { it.name == name }
    if (matches.size() != 1) {
        throw new IllegalStateException("Field '${name}' matched ${matches.size()} fields – set it in FIELD_ID_OVERRIDES")
    }
    matches[0].id as String
}
def startId   = fieldId(FIELD_START)
def endId     = fieldId(FIELD_END)
def serversId = fieldId(FIELD_SERVERS)
logger.info("Field IDs: start=${startId}, end=${endId}, servers=${serversId}")

// ---------- on update: run only if a relevant field changed ----------
def vars = binding.variables
if (vars.webhookEvent == "jira:issue_updated") {
    def items = (vars.changelog?.items ?: []) as List<Map>
    logger.info("Changed fields: " + items.collect { "${it.field} (${it.fieldId})" }.join(", "))
    boolean relevant = items.any {
        it.fieldId in [startId, endId, serversId] || it.field in [FIELD_START, FIELD_END, FIELD_SERVERS]
    }
    if (!relevant) {
        logger.info("No relevant field changed – skipping")
        return
    }
}

// ---------- load fresh issue data ----------
def issueResp = get("/rest/api/3/issue/${issueKey}")
        .queryString("fields", "summary,issuetype,${startId},${endId},${serversId}")
        .asObject(Map)
if (issueResp.status != 200) {
    throw new RuntimeException("Could not read ${issueKey}: ${issueResp.status} ${issueResp.body}")
}
def fields = issueResp.body.fields as Map
String currentIssueType = fields.issuetype?.name

// ---------- date parsing (Jira date/datetime, Assets date/datetime) ----------
def toInstant = { Object v ->
    if (v == null) return null
    String s = v.toString().trim()
    if (!s) return null
    if (s ==~ /^\d+$/)               return Instant.ofEpochMilli(s as Long)
    if (s ==~ /^\d{4}-\d{2}-\d{2}$/) return LocalDate.parse(s).atStartOfDay(TZ).toInstant()
    try { return OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant() } catch (ignored) {}
    try { return OffsetDateTime.parse(s).toInstant() } catch (ignored) {}
    try { return Instant.parse(s) } catch (ignored) {}
    try { return LocalDateTime.parse(s).atZone(TZ).toInstant() } catch (ignored) {}
    null
}

Instant activityStart = toInstant(fields[startId])
Instant activityEnd   = toInstant(fields[endId])
def servers = (fields[serversId] ?: []) as List<Map>   // [{workspaceId, id, objectId}, ...]

logger.info("Issue type=${currentIssueType} | raw start=${fields[startId]} | raw end=${fields[endId]}")
logger.info("Activity window (UTC): ${activityStart} -> ${activityEnd} | servers on issue: ${servers.size()}")

if (!activityStart || !activityEnd || !servers) {
    logger.info("Missing start/end/servers – skipping")
    return
}

// ---------- Assets API ----------
String workspaceId = servers[0].workspaceId ?:
        ((get("/rest/servicedeskapi/assets/workspace").asObject(Map).body?.values as List)?.getAt(0)?.workspaceId)

// Service-account tokens must use the /ex/jira/{cloudId} gateway
String assetsBase = cloudId ?
        "https://api.atlassian.com/ex/jira/${cloudId}/jsm/assets/workspace/${workspaceId}/v1" :
        "https://api.atlassian.com/jsm/assets/workspace/${workspaceId}/v1"
logger.info("Assets base: ${assetsBase}")

def assetsGet = { String path, Class type ->
    def r = get("${assetsBase}${path}")
            .basicAuth(assetsEmail, assetsToken)
            .header("Accept", "application/json")
            .asObject(type)
    if (r.status == 401 || r.status == 403) {
        throw new RuntimeException("Assets auth failed (${r.status}) – token expired, missing cmdb scopes, or no schema access")
    }
    if (r.status != 200) throw new RuntimeException("Assets GET ${path} failed: ${r.status} ${r.body}")
    r.body
}

def aqlSearch = { String aql ->
    def results = []
    int startAt = 0
    while (true) {
        def r = post("${assetsBase}/object/aql?startAt=${startAt}&maxResults=100&includeAttributes=true")
                .basicAuth(assetsEmail, assetsToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .body([qlQuery: aql])
                .asObject(Map)
        if (r.status == 401 || r.status == 403) {
            throw new RuntimeException("Assets auth failed (${r.status}) – token expired, missing cmdb scopes, or no schema access")
        }
        if (r.status != 200) throw new RuntimeException("AQL failed: ${r.status} ${r.body}")
        logger.info("AQL response: status=${r.status}, total=${r.body.total}, keys=${r.body.keySet()}, returned=${(r.body.values ?: []).size()}")
        def values = (r.body.values ?: []) as List<Map>
        results.addAll(values)
        if (r.body.isLast || values.isEmpty()) break
        startAt += values.size()
    }
    results
}

// attribute name -> id, cached per object type
Map<String, Map<String, String>> attrIdCache = [:]
def attrValue = { Map obj, String attrName ->
    String typeId = obj.objectType.id as String
    if (!attrIdCache.containsKey(typeId)) {
        def typeAttrs = assetsGet("/objecttype/${typeId}/attributes", List) as List<Map>
        attrIdCache[typeId] = typeAttrs.collectEntries { [(it.name as String): it.id as String] }
        logger.info("Attributes of object type ${typeId}: ${attrIdCache[typeId]}")
    }
    // case-insensitive lookup by attribute name
    String attrId = attrIdCache[typeId].find { k, v -> k.equalsIgnoreCase(attrName) }?.value
    def attr = (obj.attributes as List<Map>)?.find { (it.objectTypeAttributeId as String) == attrId }
    (attr?.objectAttributeValues as List<Map>)?.getAt(0)?.value
}

// ---------- main logic ----------
boolean overlapFound = false
Set<String> problematicServers = [] as Set
List<String> validServerKeys = []
List<Map> validServerRefs = []   // original field values – used for the update

servers.each { serverRef ->
    // auth / fetch errors here are fatal – let them throw
    def server = assetsGet("/object/${serverRef.objectId}", Map) as Map
    String serverKey  = server.objectKey
    String serverName = server.label          // label attribute (normally "Name")
    boolean serverHasOverlap = false

    // NOTE: "objectSchema = ..." returns 0 via the API with the service account token
    // (works in the UI) – so filter by object type + server only.
    String aql = """objectType = "${EXCL_TYPE}" AND Server IN ("${serverKey}")"""
    logger.info("Server ${serverName} (${serverKey}) – AQL: ${aql}")
    def exclusions = aqlSearch(aql)
    logger.info("Server ${serverName} (${serverKey}) – exclusions found: ${exclusions.size()}")

    exclusions.each { exclusion ->
        try {
            Instant exclusionStart = toInstant(attrValue(exclusion, ATTR_START))
            Instant exclusionEnd   = toInstant(attrValue(exclusion, ATTR_END))
            logger.info("  Exclusion ${exclusion.objectKey}: raw start=${attrValue(exclusion, ATTR_START)}, raw end=${attrValue(exclusion, ATTR_END)} -> ${exclusionStart} -> ${exclusionEnd}")
            if (!exclusionStart || !exclusionEnd) {
                logger.warn("  Exclusion ${exclusion.objectKey} has no start/end (check ATTR_START/ATTR_END names) – skipped")
                return
            }

            if (activityStart.isBefore(exclusionEnd) && activityEnd.isAfter(exclusionStart)) {
                serverHasOverlap = true
                overlapFound = true
                problematicServers << serverName
                logger.warn("Exclusion overlap: server=${serverName}, activity=${activityStart}->${activityEnd}, exclusion=${exclusionStart}->${exclusionEnd}")
            }
        } catch (Exception e) {
            errors << "Failed checking exclusion ${exclusion.objectKey} for ${serverName}: ${e.message}"
            logger.error(errors.last())
        }
    }

    logger.info("Server ${serverName} – overlap: ${serverHasOverlap}")
    if (!serverHasOverlap) {
        validServerKeys << serverKey
        validServerRefs << [workspaceId: serverRef.workspaceId, id: serverRef.id, objectId: serverRef.objectId]
    }
}

logger.info("Summary: overlap=${overlapFound}, remove=${problematicServers}, keep=${validServerKeys} | payload=${validServerRefs}")

if (overlapFound) {
    String serverList = problematicServers.sort().join(", ")

    // 1) remove overlapping servers
    def upd = put("/rest/api/3/issue/${issueKey}")
            .queryString("overrideScreenSecurity", true)
            .header("Content-Type", "application/json")
            // Cloud Assets field needs {workspaceId, id, objectId} – {key: ...} is silently ignored and empties the field
            .body([fields: [(serversId): validServerRefs]])
            .asString()
    if (upd.status != 204) {
        errors << "Failed updating Servers on ${issueKey}: ${upd.status} ${upd.body}"
        logger.error(errors.last())
    }

    // 2) comment (v2 endpoint = plain text)
    String comment = """קיימת חפיפה בין זמני הפעילות לבין זמני החרגת השרתים.

השרתים הבאים הוסרו מרשימת השרתים:
${serverList}"""

    def cm = post("/rest/api/2/issue/${issueKey}/comment")
            .header("Content-Type", "application/json")
            .body([body: comment])
            .asString()
    if (cm.status != 201) {
        errors << "Failed adding comment on ${issueKey}: ${cm.status} ${cm.body}"
        logger.error(errors.last())
    }

    // 3) transition
    String transitionId = currentIssueType == "Change" ? TRANSITION_CHANGE : TRANSITION_SUB_CHANGE
    def tr = post("/rest/api/3/issue/${issueKey}/transitions")
            .header("Content-Type", "application/json")
            .body([transition: [id: transitionId]])
            .asString()
    if (tr.status != 204) {
        errors << "Transition ${transitionId} failed on ${issueKey}: ${tr.status} ${tr.body}"
        logger.error(errors.last())
    }
}

logger.info("=== ExclusionTimes end: ${issueKey} | errors=${errors.size()} ===")

// ---------- fail loudly so ScriptRunner sends the failure email ----------
if (errors) {
    throw new RuntimeException("ExclusionTimes finished with ${errors.size()} error(s) on ${issueKey}:\n" + errors.join("\n"))
}

/*
 * EMAIL – not possible from ScriptRunner Cloud to an external address.
 * Use a Jira Automation rule:
 *   Trigger   : Work item commented
 *   Condition : {{comment.body}} contains "קיימת חפיפה בין זמני הפעילות"
 *   Action    : Send email
 *               To      : shintashtit@harel-ins.co.il
 *               Subject : חפיפה עם החרגת שרתים - {{issue.key}}
 *               Body    : נמצאה חפיפה מול החרגת שרתים.
 *                         שינוי: {{issue.key}}
 *                         תיאור: {{issue.summary}}
 *                         {{comment.body}}
 */

