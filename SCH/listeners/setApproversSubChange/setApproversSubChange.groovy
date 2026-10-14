/*
 * setApproversSubChange - Jira Cloud port of DC script Automation/setApproversSubChangee (PMO-4745)
 *
 * ScriptRunner for Jira Cloud - Script Listener
 *   Events    : Issue Created, Issue Updated
 *   Condition : merged Jira expression (Request OR Change/Sub Change)
 *   Run as    : ScriptRunner Add-on user
 *
 * Replaces BOTH DC automation rules + the DC script body.
 * Behaviour is kept 1:1 with DC, including order-dependent and failure behaviour.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// ============================== CONFIG ==============================
final String CF_FIRST_APPROVERS  = 'customfield_17554' // First Approvers
final String CF_SECOND_APPROVERS = 'customfield_17552' // Second Approvers
final String CF_CLASSIFICATION   = 'customfield_18266' // סיווג קריטיות השינוי
final String CF_CHANGE_INFO      = 'customfield_18267' // שינוי לידיעה/תיאום
final String CF_INFO_SECURITY    = 'customfield_18162' // עדכון אבטחת מידע
final String CF_SERVERS          = 'customfield_18196' // Servers
final String CF_AFFECTED_SYSTEMS = 'customfield_12345' // מערכות מושפעות חדש (DC 20900)
final String CF_SYSTEM           = 'customfield_12345' // מערכת (DC 19107)
final String CF_APPROVERS        = 'customfield_10003' // Approvers
final String CF_TO_INFORM        = 'customfield_17447' // גורמים לידוע
final String CF_CHANGE_TYPE      = 'customfield_18195' // סוג השינוי (DC Automation 1 condition)

// Cloud accountId(s) equivalent to DC {{initiator.name}} == "admin"
// (checked in the script: in the listener Condition, `user` is the ScriptRunner Add-on user, not the initiator)
final Set<String> ADMIN_INITIATOR_ACCOUNT_IDS = ['712020:72580e85-fcb5-408b-8fb6-3e67747d1e85'] as Set

final String ASSETS_SCHEMA   = 'it_operations'   // DC: "IT Operations" / ObjectSchemaId = 14
// Cloud Assets names (DC name in comment)
final String OT_SERVER       = 'server'            // Server
final String OT_SYSTEM       = 'system'            // System
final String OT_DATABASE     = 'Database'          // Database
final String OT_ROLES        = 'user_system_roles' // Users System Roles
final String AT_SERVERS      = 'servers'           // Servers (on system and Database)
final String AT_CONSOLIDATION= 'is_consolidation_server' // Is Consolidatation Server
final String AT_SYS_MANAGER  = 'system_manager'    // System Manager (ref -> org_directory.users)
final String AT_MAIN_SECOND  = 'main_seconds'      // Main / Second
final String AT_JOB_DESC     = 'job_description'   // Job Description
final String AT_ROLE_SYSTEM  = 'system'            // System (on roles)
final String AT_ROLE_USER    = 'user'              // User (ref -> org_directory.users)
final String AT_USER_ACCOUNT = 'jira_account_id'   // on org_directory.users
final String AT_USER_JIRA    = 'jira_user'         // on org_directory.users (fallback)
final String FIELD_APPROVERS = 'Approvers'
final String FIELD_TO_INFORM = 'גורמים לידוע'
final String LOG_PREFIX      = 'Automaation: setApproversSubChange: '

// ===================== TRIGGER GATE =====================
// Type / field-value checks are in the listener Condition (Jira expression).
// Checks the Condition cannot do stay here:
//   - Request    -> only on Issue Created
//   - Change/Sub -> only on Issue Updated, classification changed to a value in THIS event, initiator = admin
String issueKey    = issue.key as String
boolean isRequest  = (issue.fields?.issuetype?.name as String) == 'Request'

if (webhookEvent == 'jira:issue_created') {
    if (!isRequest) return                       // Change/Sub Change created - DC rule 1 is update-only
} else if (webhookEvent == 'jira:issue_updated') {
    if (isRequest) return                        // Request updated - DC rule 2 is create-only
    def item = (changelog?.items ?: []).find { it['fieldId'] == CF_CLASSIFICATION }
    if (!item || !(item['toString'] as String)) {
        logger.info(LOG_PREFIX + "SKIP ${issueKey}: ${CF_CLASSIFICATION} not changed to a value in this event")
        return
    }
    // Initiator of THIS event (webhook user); fallback: author of the matching changelog entry
    String initiatorId = binding.hasVariable('user') ? (user?.accountId as String) : null
    if (!initiatorId && changelog?.id) {
        def cr = post("/rest/api/3/issue/${issueKey}/changelog/list")
                .header('Content-Type', 'application/json')
                .body([changelogIds: [changelog.id as Long]])
                .asObject(Map)
        initiatorId = (cr.body?.values?.getAt(0)?.author?.accountId) as String
    }
    if (!ADMIN_INITIATOR_ACCOUNT_IDS.contains(initiatorId)) {
        logger.info(LOG_PREFIX + "SKIP ${issueKey}: initiator ${initiatorId} is not admin")
        return
    }
} else {
    return
}
logger.warn(LOG_PREFIX + "RUN ${issueKey} (${webhookEvent})")

// ============================== HELPERS ==============================
def asList = { v -> v == null ? [] : (v instanceof Collection ? (v as List) : [v]) }
def slurper = new JsonSlurper()
// Basic auth header for the Assets API (no Unirest import needed)
final String ASSETS_AUTH = 'Basic ' + "${ASSETS_USER_EMAIL}:${ASSETS_API_TOKEN}".toString().bytes.encodeBase64().toString()

// --- Assets (lazy init: DC touches Assets only on paths that need it) ---
String assetsBase = null
def assetsUrl = { String path ->
    if (assetsBase == null) {
        def ws = get('/rest/servicedeskapi/assets/workspace').asObject(Map)
        String wid = ws.body?.values?.getAt(0)?.workspaceId
        if (!wid) {
            throw new IllegalStateException("Assets workspace not found: ${ws.status} ${ws.body}")
        }
        assetsBase = "https://api.atlassian.com/jsm/assets/workspace/${wid}/v1"
    }
    assetsBase + path
}

def assetsGet = { String path ->
    def r = get(assetsUrl(path))
            .header('Authorization', ASSETS_AUTH)
            .header('Accept', 'application/json')
            .asString()
    if (r.status != 200) {
        throw new IllegalStateException("Assets GET ${path} failed: ${r.status} ${r.body}")
    }
    slurper.parseText(r.body)
}

// Returns ALL matches (DC Assets.search returns all). Throws on invalid AQL, like DC.
def assetsSearch = { String aql ->
    List all = []
    int startAt = 0
    int pageSize = 100
    while (true) {
        def r = post(assetsUrl("/object/aql?startAt=${startAt}&maxResults=${pageSize}&includeAttributes=true"))
                .header('Authorization', ASSETS_AUTH)
                .header('Accept', 'application/json')
                .header('Content-Type', 'application/json')
                .body(JsonOutput.toJson([qlQuery: aql]))
                .asString()
        if (r.status != 200) {
            throw new IllegalStateException("AQL failed (${r.status}): ${aql} -> ${r.body}")
        }
        def page = slurper.parseText(r.body)
        List vals = (page.values ?: []) as List
        all.addAll(vals)
        if (page.isLast == true || vals.size() < pageSize) {
            break
        }
        startAt += vals.size()
    }
    all
}

def getObject = { id -> assetsGet("/object/${id}") as Map }

// objectTypeId -> [attributeId: attributeName]
Map<String, Map<String, String>> attrNameCache = [:]
def attrValues = { Map obj, String attrName ->
    String otId = obj.objectType?.id as String
    if (!attrNameCache.containsKey(otId)) {
        attrNameCache[otId] = (assetsGet("/objecttype/${otId}/attributes") as List)
                .collectEntries { [(it.id as String): (it.name as String)] }
    }
    Map<String, String> names = attrNameCache[otId]
    def attr = (obj.attributes ?: []).find { names[it.objectTypeAttributeId as String] == attrName }
    (attr?.objectAttributeValues ?: []) as List
}

// DC ObjectBean.getString(name)
def getStringAttr = { Map obj, String attrName ->
    def vals = attrValues(obj, attrName)
    if (vals) {
        return vals[0].value
    }
    attrName == 'Key' ? obj.objectKey : null
}

// DC ObjectBean.getBoolean(name) -> null when empty
def getBooleanAttr = { Map obj, String attrName ->
    def v = getStringAttr(obj, attrName)
    v == null ? null : v.toString().toBoolean()
}

// DC getReference(...).getName() -> Cloud accountId.
// In Cloud the attribute is an object ref to org_directory.users; accountId = users.jira_account_id
// (fallback users.jira_user). First value only, like DC.
Map<String, String> usersAccountCache = [:]
def getUserRef = { Map obj, String attrName ->
    def vals = attrValues(obj, attrName)
    if (!vals) {
        return null
    }
    def first = vals[0]
    if (first.user) {
        return (first.user.accountId ?: first.user.key) as String
    }
    def refId = first.referencedObject?.id
    if (refId == null) {
        return null
    }
    String k = refId as String
    if (!usersAccountCache.containsKey(k)) {
        Map u = getObject(refId)
        String acc = getStringAttr(u, AT_USER_ACCOUNT) as String
        if (!acc) {
            def ju = attrValues(u, AT_USER_JIRA)
            acc = ju ? ((ju[0].user?.accountId ?: ju[0].user?.key ?: ju[0].value) as String) : null
        }
        if (!acc) {
            logger.warn(LOG_PREFIX + "users object ${u.objectKey} '${u.label}' has no ${AT_USER_ACCOUNT} / ${AT_USER_JIRA}")
        }
        usersAccountCache[k] = acc
    }
    usersAccountCache[k]
}

// ============================== LOAD ISSUE ==============================
def issueResp = get("/rest/api/2/issue/${issueKey}").asObject(Map)
if (issueResp.status != 200) {
    throw new IllegalStateException("Failed to load ${issueKey}: ${issueResp.status} ${issueResp.body}")
}
Map fields = issueResp.body.fields as Map

String issueType     = fields.issuetype?.name as String
def firstApprovers   = asList(fields[CF_FIRST_APPROVERS])
def secondApprovers  = asList(fields[CF_SECOND_APPROVERS])
def classification   = fields[CF_CLASSIFICATION]?.value
def changeType       = fields[CF_CHANGE_INFO]?.value
def infoSecurity     = fields[CF_INFO_SECURITY]?.value
// DC reads "שרתים אפליקטיביים" for Request but never uses it; the Request branch never touches servers.
def servers          = (issueType == 'Request') ? [] : asList(fields[CF_SERVERS])

String fieldToSet = FIELD_APPROVERS
Set<String> systemManagerList = [] as LinkedHashSet   // DC: Set<String> = [] -> LinkedHashSet (insertion order)
Set<String> approversToSkip   = [] as LinkedHashSet
approversToSkip.addAll(firstApprovers.collect { it.accountId as String })
approversToSkip.addAll(secondApprovers.collect { it.accountId as String })

List<Map> approversList           = []   // inactive users
List<Map> approversListWoInactive = []   // active, not skipped

def addRoleUser = { String roleName, Map systemAssetObject ->
    String aql = """
        objectSchema = "${ASSETS_SCHEMA}"
        and objecttype = "${OT_ROLES}"
        AND "${AT_JOB_DESC}" IN ("${roleName}")
        AND "${AT_ROLE_SYSTEM}" IN (${systemAssetObject.objectKey})
    """.toString()
    def searchResult = assetsSearch(aql)
    String accountId = searchResult ? getUserRef(searchResult[0] as Map, AT_ROLE_USER) : null
    if (accountId) {
        logger.warn("Found user for role ${roleName}: ${accountId}")
        systemManagerList.add(accountId)
    }
}

def addSystemManager = { Map systemAssetObject ->
    String systemUserRef = getUserRef(systemAssetObject, AT_SYS_MANAGER)
    if (systemUserRef) {
        logger.warn(LOG_PREFIX + "systemUsernameRef " + systemUserRef)
        systemManagerList.add(systemUserRef)
    }
}

// DC paths A and B: manager + role users
def processSystemWithRoles = { Map systemAssetObject ->
    addSystemManager(systemAssetObject)
    addRoleUser('גורם מאשר השבתות', systemAssetObject)
    if ((infoSecurity as String) == 'כן') {
        addRoleUser('גורם ליידוע על כל פעילויות / השבתות', systemAssetObject)
    }
}

// ============================== MAIN LOGIC ==============================
if (issueType == 'Request') {
    fieldToSet = FIELD_APPROVERS
    String reporterId = fields.reporter?.accountId as String

    List allSystems = []
    asList(fields[CF_AFFECTED_SYSTEMS]).each { allSystems << getObject(it.objectId) } // מערכות מושפעות חדש
    asList(fields[CF_SYSTEM]).each { allSystems << getObject(it.objectId) }           // מערכת

    allSystems.unique { it.objectKey }.each { Map systemAssetObject ->
        String managerId = getUserRef(systemAssetObject, AT_SYS_MANAGER)
        if (managerId && managerId != reporterId) {
            logger.warn("Request: adding system manager ${managerId}")
            systemManagerList.add(managerId)
        }
    }

} else if (servers && servers.size() > 0) { // Change / Sub Change
    for (int i = 0; i < servers.size(); i++) {
        Map server = getObject(servers[i].objectId)
        String currentServerKey  = String.valueOf(getStringAttr(server, 'Key'))   // DC: .toString() -> "null" if empty
        String currentServerName = String.valueOf(getStringAttr(server, 'Name'))

        if ((classification as String)?.equalsIgnoreCase('Standard') ||
            (changeType as String)?.equalsIgnoreCase('ידיעה בלבד')) {
            // ---- Path A ----
            fieldToSet = FIELD_TO_INFORM
            def assetObject = assetsSearch("objectSchema = \"${ASSETS_SCHEMA}\" and objectType = ${OT_SERVER} and Key = ${currentServerKey}".toString())[0] // unused, kept for parity
            def systemAssetObjects = assetsSearch("objectSchema = \"${ASSETS_SCHEMA}\" and objectType = ${OT_SYSTEM} and ${AT_SERVERS} = ${currentServerKey}".toString())
            if (systemAssetObjects) {
                systemAssetObjects.each { processSystemWithRoles(it as Map) }
            }
        } else {
            boolean isConsolidation = getBooleanAttr(server, AT_CONSOLIDATION) ?: false // DC: null -> false
            if (!isConsolidation) {
                // ---- Path B ---- (fieldToSet unchanged, as in DC)
                def assetObject = assetsSearch("objectSchema = \"${ASSETS_SCHEMA}\" and objectType = ${OT_SERVER} and Key = ${currentServerKey}".toString())[0] // unused, kept for parity
                def systemAssetObjects = assetsSearch("objectSchema = \"${ASSETS_SCHEMA}\" and objectType = ${OT_SYSTEM} and ${AT_SERVERS} = ${currentServerKey}".toString())
                if (systemAssetObjects) {
                    systemAssetObjects.each { processSystemWithRoles(it as Map) }
                }
            } else {
                // ---- Path C ---- consolidation server
                // Unquoted Name preserved from DC: names with spaces/special chars make the AQL fail (throws)
                String aqlForDbToServer = """objectSchema = "${ASSETS_SCHEMA}" AND objecttype = ${OT_DATABASE} AND "${AT_SERVERS}" = ${currentServerName}""".toString()
                def relevantDbs = assetsSearch(aqlForDbToServer)
                logger.warn(LOG_PREFIX + "relevantDbs " + relevantDbs.collect { it.objectKey })
                if (relevantDbs) {
                    def systemAssetObjects = assetsSearch("objectSchema = \"${ASSETS_SCHEMA}\" and objectType = ${OT_SYSTEM} and ${AT_SERVERS} = ${currentServerKey}".toString())
                    if (systemAssetObjects) {
                        systemAssetObjects.each { Map systemAssetObject ->
                            logger.warn(LOG_PREFIX + "systemAssetObject " + systemAssetObject.objectKey)
                            addSystemManager(systemAssetObject)
                            // DC: last DB evaluated wins
                            relevantDbs.each { Map db ->
                                String mainOrSecond = getStringAttr(db, AT_MAIN_SECOND) as String
                                logger.warn("server: ${currentServerName} | Database: ${db.objectKey} | Main/second: ${mainOrSecond ?: 'NULL'}")
                                fieldToSet = (mainOrSecond != 'Second') ? FIELD_APPROVERS : FIELD_TO_INFORM
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================== RESOLVE USERS ==============================
logger.warn("systemManagerList " + systemManagerList)
systemManagerList.each { String accountId ->
    def r = get('/rest/api/3/user').queryString('accountId', accountId).asObject(Map)
    if (r.status != 200 || !r.body) {
        // DC: Users.getByName -> null -> NPE on isActive() -> whole script aborts (no comment, no field update)
        throw new IllegalStateException("User not found for accountId ${accountId} (${r.status})")
    }
    Map u = r.body as Map
    if (u.active) {
        if (!approversToSkip.contains(accountId)) {
            approversListWoInactive.add(u)
        }
    } else {
        approversList.add(u)
    }
}

// ============================== INACTIVE COMMENT ==============================
String inActiveUsers = "היוזרים הבאים לא פעילים ומוגדרים מנהלי מערכות / גורמים מאשרי השבתות" + "\n"
if (approversList && approversList.size() > 0) {
    approversList.each { inActiveUsers += (it.displayName as String) + " ," }
    def c = post("/rest/api/2/issue/${issueKey}/comment")
            .header('Content-Type', 'application/json')
            .body([
                    body      : inActiveUsers,
                    properties: [[key: 'sd.public.comment', value: [internal: true]]]
            ])
            .asString()
    if (c.status != 201) {
        throw new IllegalStateException("Failed to add internal comment: ${c.status} ${c.body}")
    }
}

// ============================== SET FIELD ==============================
def setUsersField = { String cfId, Collection<String> accountIds ->
    def r = put("/rest/api/2/issue/${issueKey}")
            .header('Content-Type', 'application/json')
            .body([fields: [(cfId): accountIds.collect { [accountId: it] }]])
            .asString()
    if (r.status != 204) {
        throw new IllegalStateException("Failed to set ${cfId}: ${r.status} ${r.body}")
    }
}

if (fieldToSet == FIELD_TO_INFORM) {
    // merge with existing value
    Set<String> finalUsers = [] as LinkedHashSet
    asList(fields[CF_TO_INFORM]).each { finalUsers.add(it.accountId as String) }
    approversListWoInactive.each { finalUsers.add(it.accountId as String) }
    setUsersField(CF_TO_INFORM, finalUsers)
} else {
    // overwrite (clears the field when the list is empty, as in DC)
    setUsersField(CF_APPROVERS, approversListWoInactive.collect { it.accountId as String })
}
