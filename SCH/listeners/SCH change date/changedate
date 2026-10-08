/*
 * Change Declined mail + labels + cancel linked Tasks – ScriptRunner for Jira Cloud
 *
 * Where to run: ScriptRunner > Script Listeners
 *   Event:     Issue Updated
 *   Run as:    ScriptRunner Add-on User (needs Edit + Transition permissions)
 *   Condition: ['Change', 'Request', 'Sub Change'].includes(issue.issueType.name)
 *              && new Date().getTime() - issue.created.getTime() > 60000
 *
 * Flow (only when TRIGGER_FIELD changes TO TRIGGER_VALUE):
 *   1. Collect recipients and send the declined mail
 *   2. Add label "date_changed" to the issue
 *   3. For every issue linked with the "Created" link type that is a Task:
 *        add label "date_changed" and transition it to "Cancelled"
 */

final String TRIGGER_FIELD_NAME  = "Team ID"
final String TRIGGER_VALUE       = "123"
final List<String> CANCELLED     = ["Cancelled", "Canceled"]
final String APPROVER_FIELD_NAME = "Involved Teams Approvers"

final String LABEL               = "date_changed"
final String CREATED_LINK_TYPE   = "Defect"      // link type NAME (outward wording: "created")
final String TASK_TYPE           = "Task"
final String CANCEL_STATUS       = "Cancelled"

def issueKey = issue.key as String

// ---------- Resolve custom field IDs by name ----------
def allFields = get("/rest/api/3/field").asObject(List).body as List<Map>
String triggerFieldId  = allFields.find { it.name == TRIGGER_FIELD_NAME }?.id as String
String approverFieldId = allFields.find { it.name == APPROVER_FIELD_NAME }?.id as String

// ---------- Gate: run only when the trigger field changed to the trigger value ----------
def items = (changelog?.items ?: []) as List<Map>
def triggerChange = items.find { Map it ->
    (it.fieldId == triggerFieldId || it.field == TRIGGER_FIELD_NAME) &&
    (it.toString as String)?.trim() == TRIGGER_VALUE &&
    (it.fromString as String)?.trim() != TRIGGER_VALUE
}
if (!triggerChange) {
    return
}
logger.warn("${issueKey}: ${TRIGGER_FIELD_NAME} changed '${triggerChange.fromString}' -> '${triggerChange.toString}'")

// ---------- Fetch the issue ----------
def fieldList = ["summary", "assignee", "reporter", "issuelinks"]
if (approverFieldId) fieldList << approverFieldId

def issueResp = get("/rest/api/3/issue/${issueKey}")
        .queryString("fields", fieldList.join(","))
        .asObject(Map)
if (issueResp.status != 200) {
    logger.warn("Issue not found for key: ${issueKey} (HTTP ${issueResp.status})")
    return
}

Map f = (issueResp.body as Map).fields as Map
String summary = (f.summary ?: "אין") as String
List<Map> links = (f.issuelinks ?: []) as List<Map>

// =====================================================================
// 1. RECIPIENTS + MAIL
// =====================================================================
Set<String> accountIds = [] as Set<String>
def addUser = { Object u ->
    if (u instanceof Map && u.accountId && u.active != false) {
        accountIds << (u.accountId as String)
    }
}

addUser(f.assignee)
addUser(f.reporter)

links.each { Map link ->
    Map linked = (link.outwardIssue ?: link.inwardIssue) as Map
    if (!linked) return
    String statusName = ((linked.fields as Map)?.status as Map)?.name as String
    if (statusName in CANCELLED) return
    def li = get("/rest/api/3/issue/${linked.key}").queryString("fields", "assignee").asObject(Map)
    if (li.status == 200) addUser(((li.body as Map).fields as Map)?.assignee)
}

if (approverFieldId) {
    def val = f[approverFieldId]
    if (val instanceof Collection) val.each { addUser(it) } else addUser(val)
}

def apprResp = get("/rest/servicedeskapi/request/${issueKey}/approval").asObject(Map)
if (apprResp.status == 200) {
    ((apprResp.body as Map).values as List<Map>)?.each { Map approval ->
        (approval.approvers as List<Map>)?.each { Map ap -> addUser(ap.approver) }
    }
}

logger.warn("Recipients count: ${accountIds.size()} | accountIds: ${accountIds}")

if (accountIds) {
    String subject = "הודעת דחייה: שינוי ${issueKey} - ${summary}"
    String htmlBody = """
<p style="direction: rtl; text-align: right; font-family: Arial, Helvetica, sans-serif; font-size: 14px;">
היי,<br><br>
שים לב! השינוי <strong>${summary}</strong> נדחה.<br><br>
השינוי יתואם מחדש ויעבור לאישורך.<br><br>
בברכה,<br>
צוות שינויים<br>
החטיבה הטכנולוגית
</p>
"""
    String textBody = "היי,\n\nשים לב! השינוי ${summary} נדחה.\n\nהשינוי יתואם מחדש ויעבור לאישורך.\n\nבברכה,\nצוות שינויים\nהחטיבה הטכנולוגית"

    def notifyResp = post("/rest/api/3/issue/${issueKey}/notify")
            .header("Content-Type", "application/json")
            .body([subject: subject, textBody: textBody, htmlBody: htmlBody,
                   to     : [users: accountIds.collect { [accountId: it] }]])
            .asString()

    if (notifyResp.status == 204) {
        logger.warn("Sent change-declined mail for ${issueKey} to ${accountIds.size()} users")
    } else {
        logger.warn("Failed sending mail for ${issueKey}: HTTP ${notifyResp.status} - ${notifyResp.body}")
    }
} else {
    logger.warn("No recipients found for ${issueKey}. Skipping mail.")
}

// =====================================================================
// Helpers
// =====================================================================
def addLabel = { String key ->
    def r = put("/rest/api/3/issue/${key}")
            .header("Content-Type", "application/json")
            .body([update: [labels: [[add: LABEL]]]])
            .asString()
    if (r.status == 204) {
        logger.warn("Label '${LABEL}' added to ${key}")
    } else {
        logger.warn("Failed adding label to ${key}: HTTP ${r.status} - ${r.body}")
    }
}

def transitionTo = { String key, String targetStatus ->
    def tr = get("/rest/api/3/issue/${key}/transitions")
            .queryString("expand", "transitions.fields")
            .asObject(Map)
    if (tr.status != 200) {
        logger.warn("Could not read transitions for ${key}: HTTP ${tr.status} - ${tr.body}")
        return
    }
    List<Map> transitions = ((tr.body as Map).transitions ?: []) as List<Map>
    logger.warn("${key} available transitions: " +
            transitions.collect { "'${it.name}' -> '${(it.to as Map)?.name}' (id ${it.id})" })

    // Accept both spellings
    List<String> targets = [targetStatus, "Cancelled", "Canceled"]*.toLowerCase().unique()
    Map t = transitions.find { (((it.to as Map)?.name as String)?.toLowerCase()) in targets } ?:
            transitions.find { ((it.name as String)?.toLowerCase()) in targets }
    if (!t) {
        logger.warn("No transition to '${targetStatus}' available for ${key} (check current status / transition conditions)")
        return
    }

    // Fill required transition-screen fields we can handle (e.g. Resolution)
    Map fieldsToSet = [:]
    ((t.fields ?: [:]) as Map).each { String fid, Object meta ->
        Map m = meta as Map
        if (m.required && !m.hasDefaultValue) {
            List<Map> allowed = (m.allowedValues ?: []) as List<Map>
            if (fid == "resolution" && allowed) {
                Map res = allowed.find { ((it.name as String)?.toLowerCase()) in ["cancelled", "canceled", "won't do", "declined"] } ?: allowed[0]
                fieldsToSet.resolution = [id: res.id]
                logger.warn("${key}: setting required Resolution = '${res.name}'")
            } else {
                logger.warn("${key}: transition requires field '${m.name}' (${fid}) – not set by script")
            }
        }
    }

    Map body = [transition: [id: t.id]]
    if (fieldsToSet) body.fields = fieldsToSet

    def r = post("/rest/api/3/issue/${key}/transitions")
            .header("Content-Type", "application/json")
            .body(body)
            .asString()
    if (r.status == 204) {
        logger.warn("${key} transitioned to '${targetStatus}' (transition '${t.name}')")
    } else {
        logger.warn("Failed transitioning ${key}: HTTP ${r.status} - ${r.body}")
    }
}

// =====================================================================
// 2. LABEL ON THE TRIGGER ISSUE
// =====================================================================
addLabel(issueKey)


// =====================================================================
// 3. LINKED TASKS VIA "Created" LINK → LABEL + CANCEL
// =====================================================================
// Log every link so mismatches are visible
links.each { Map link ->
    Map lt = link.type as Map
    Map li = (link.outwardIssue ?: link.inwardIssue) as Map
    logger.warn("Link: ${li?.key} | type name='${lt?.name}' inward='${lt?.inward}' outward='${lt?.outward}' | " +
            "direction=${link.outwardIssue ? 'outward' : 'inward'} | " +
            "issuetype='${((li?.fields as Map)?.issuetype as Map)?.name}' status='${((li?.fields as Map)?.status as Map)?.name}'")
}

// Link type "Defect" (outward wording "created") – OUTWARD only = issues this issue created
def isCreatedLink = { Map link ->
    ((link.type as Map)?.name as String)?.equalsIgnoreCase(CREATED_LINK_TYPE) && link.outwardIssue != null
}

links.findAll { Map link -> isCreatedLink(link) }.each { Map link ->
    Map linked = (link.outwardIssue ?: link.inwardIssue) as Map
    if (!linked) return

    Map lf = linked.fields as Map
    String linkedType   = (lf?.issuetype as Map)?.name as String
    String linkedStatus = (lf?.status as Map)?.name as String

    if (linkedType != TASK_TYPE) return

    addLabel(linked.key as String)

    if (linkedStatus in CANCELLED) {
        logger.warn("${linked.key} already '${linkedStatus}' – skipping transition")
    } else {
        transitionTo(linked.key as String, CANCEL_STATUS)
    }
}

// 4. Clear Team ID on the trigger issue
if (triggerFieldId) {
    Map clearBody = [fields: [(triggerFieldId): null]]

    def clr = put("/rest/api/3/issue/${issueKey}")
            .header("Content-Type", "application/json")
            .body(clearBody)
            .asString()

    // Field not on the Edit screen → retry bypassing the screen
    if (clr.status == 400) {
        clr = put("/rest/api/3/issue/${issueKey}")
                .queryString("overrideScreenSecurity", "true")
                .header("Content-Type", "application/json")
                .body(clearBody)
                .asString()
    }

    logger.warn(clr.status == 204
            ? "${TRIGGER_FIELD_NAME} cleared on ${issueKey}"
            : "Failed clearing ${TRIGGER_FIELD_NAME}: HTTP ${clr.status} - ${clr.body}")
}
