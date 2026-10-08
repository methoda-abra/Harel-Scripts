/*
 * MailChangeDone - ScriptRunner for Jira Cloud - Script Listener
 * Event: Issue Updated
 * Listener Condition (Jira Expression):
 *   ['Request', 'Change'].includes(issue.issueType.name) && ['Done', 'Canceled'].includes(issue.status.name)
 * Script Variables required (ScriptRunner -> Script Variables):
 *   AUTOMATION_WEBHOOK_URL, AUTOMATION_WEBHOOK_SECRET  (Automation rule that emails shintashtit@ on Canceled)
 * Migrated from the DC automation script; business logic preserved.
 */
import java.text.SimpleDateFormat

// ================= CONFIG =================
def ALLOWED_ISSUE_TYPES  = ['Request', 'Change']
def TARGET_STATUSES      = ['Done', 'Canceled','Cancelled']
def CLOSE_REASON_FIELD   = 'סיבת הסגירה'
def APPROVER_FIELD_NAMES = [
    'First Approvers',
    'Second Approvers',
    'Involved Teams Approvers',
    'Approvers',
    'Department Executives Approvers',
    'גורמים לידוע'
]
int    COMMENT_WINDOW_MIN = 5
String TIME_ZONE          = 'Asia/Jerusalem'   // DC JVM was local time; Cloud runs in UTC

// CC mailbox: /notify can only send to Atlassian accounts / JSM customers.
// If lookup by email fails (hidden emails), paste the accountId below.
String CC_MAILBOX_EMAIL      = 'jiraimaprod@harel-ins.co.il'
String CC_MAILBOX_ACCOUNT_ID = ''

// ================= GUARD: only a real transition to Done/Canceled =================
def statusChange     = changelog?.items?.find { it['field'] == 'status' }
String toStatus      = statusChange ? statusChange['toString'] as String : null
String issueTypeName = issue?.fields?.issuetype?.name as String
if (!(issueTypeName in ALLOWED_ISSUE_TYPES) || !(toStatus in TARGET_STATUSES)) {
    logger.info("Skipping ${issue?.key}: type=${issueTypeName}, toStatus=${toStatus}")
    return
}

String issueKey = issue.key as String
logger.warn("=== MailChangeDone start: ${issueKey}, type=${issueTypeName}, toStatus=${toStatus} ===")

// ================= LOAD FRESH ISSUE =================
Map<String, String> fieldIdsByName = getFieldIdsByName([CLOSE_REASON_FIELD] + APPROVER_FIELD_NAMES)
List<String> fieldsToFetch = ['summary', 'status', 'assignee', 'reporter', 'issuelinks'] + (fieldIdsByName.values() as List)

def issueResp = get("/rest/api/3/issue/${issueKey}")
        .queryString('fields', fieldsToFetch.join(','))
        .asObject(Map)
if (issueResp.status != 200) {
    logger.warn("Could not load issue ${issueKey}: ${issueResp.status}")
    return
}
Map fields = issueResp.body.fields as Map

String issueStatus = fields.status?.name as String
String summary     = (fields.summary ?: '') as String
def closeVal       = fieldIdsByName[CLOSE_REASON_FIELD] ? fields[fieldIdsByName[CLOSE_REASON_FIELD]] : null
String closeReason = extractOptionValue(closeVal)
boolean isDone     = (issueStatus == 'Done')
boolean isCanceled = (issueStatus == 'Canceled' ||issueStatus == 'Cancelled' )

Map recent = getLatestRecentComment(issueKey, COMMENT_WINDOW_MIN, TIME_ZONE)
logger.warn("Close reason: ${closeReason}, recent comment exists: ${recent.exists}")

// ================= DECIDE FLOW BY STATUS =================
Map<String, Map> recipients = [:]   // accountId -> user (dedupe, like Set<ApplicationUser>)

if (isDone) {
    recipients.putAll(getAllAssignees(fields))
    recipients.putAll(getAllApproversFromFields(fields, fieldIdsByName, APPROVER_FIELD_NAMES))
    if (fields.reporter?.accountId) recipients[fields.reporter.accountId as String] = fields.reporter as Map
} else if (isCanceled) {
    recipients.putAll(getApproversFromApprovals(issueKey))
}

logger.warn("Recipients before filter: ${recipients.values().collect { "${it.displayName} active=${it.active} type=${it.accountType}" }}")

// DC filtered on "has email"; in Cloud emails are often hidden -> active, non-app accounts
recipients = recipients.findAll { k, u -> isMailableUser(u) }

logger.warn("toUsers: ${recipients.values().collect { it.displayName }}")
logger.warn("isDone: ${isDone}")
logger.warn("isCanceled: ${isCanceled}")

if (recipients.isEmpty()) {
    logger.warn("No recipients found for issue ${issueKey}. Aborting email send.")
    return
}

// ================= BUILD MAIL (unchanged from DC) =================
String subject
String htmlBody

if (isDone) {
    String withComment = """
השינוי <strong>${summary}</strong> – <strong>${textToHtml(closeReason)}</strong><br>
נוספה תגובה ב־${recent.created}:<br>
<blockquote style="border-right:3px solid #ccc;padding-right:8px;margin:4px 0;white-space:pre-wrap;">
${textToHtml(recent.body)}
</blockquote>
""".trim()

    String noComment = """
השינוי <strong>${summary}</strong> – <strong>${textToHtml(closeReason)}</strong>
""".trim()

    subject = "השינוי ${issueKey} - ${summary} נסגר"
    htmlBody = """
<div style="direction: rtl; text-align: right; font-family: Arial, Helvetica, sans-serif; font-size: 14px;">
  <p>${recent.exists ? withComment : noComment}</p>
  <br>
  <p>בברכה,<br>צוות שינויים<br>החטיבה הטכנולוגית</p>
</div>
""".trim()
} else {
    subject = "השינוי ${issueKey} - ${summary} בוטל"
    htmlBody = """
<div style="direction: rtl; text-align: right; font-family: Arial, Helvetica, sans-serif; font-size: 14px;">
  <p>היי,</p>
  <p>השינוי <strong>${summary}</strong> בוטל.</p>
  <br>
  <p>בברכה,<br>צוות שינויים<br>החטיבה הטכנולוגית</p>
</div>
""".trim()
}

// ================= SEND =================
String ccId = CC_MAILBOX_ACCOUNT_ID ?: resolveAccountIdByEmail(CC_MAILBOX_EMAIL)
if (!ccId) logger.warn("CC mailbox ${CC_MAILBOX_EMAIL} not resolved to an account - CC skipped")

recipients.each { String accId, Map u ->
    List<String> to = [accId]
    if (ccId && ccId != accId) to << ccId          // emulates setCc(...) on each mail
    if (sendNotification(issueKey, subject, htmlBody, to)) {
        logger.warn("Sent mail to: ${u.displayName} (${accId})")
    } else {
        logger.warn("Failed sending mail to ${u.displayName} (${accId})")
    }
}

// Canceled: separate mail to shintashtit@harel-ins.co.il via Automation incoming webhook
if (isCanceled) {
    String hookUrl    = binding.hasVariable('AUTOMATION_WEBHOOK_URL')    ? AUTOMATION_WEBHOOK_URL as String    : null
    String hookSecret = binding.hasVariable('AUTOMATION_WEBHOOK_SECRET') ? AUTOMATION_WEBHOOK_SECRET as String : null
    if (!hookUrl) {
        logger.warn("AUTOMATION_WEBHOOK_URL script variable missing - cancel mailbox mail not sent")
    } else {
        def hookResp = post(hookUrl)
                .header('Content-Type', 'application/json')
                .header('X-Automation-Webhook-Token', hookSecret ?: '')
                .body([
                    issues: [issueKey],
                    data  : [subject: subject, htmlBody: htmlBody]
                ])
                .asString()
        logger.warn("Cancel mail via Automation webhook: ${hookResp.status} ${hookResp.body ?: ''}")
    }
}

logger.warn("=== MailChangeDone end: ${issueKey} ===")

// =====================================================================
// ============================ HELPERS ================================
// =====================================================================

String textToHtml(def value) {
    if (value == null) return "אין"
    def txt = value as String
    if (!txt?.trim()) return "אין"
    return txt.replaceAll("\\r?\\n", "<br>")
}

String extractOptionValue(def val) {
    if (val == null) return "אין"
    if (val instanceof Map && val.containsKey('value')) return (val.value ?: "אין") as String   // select / radio
    return (val.toString() ?: "אין")
}

boolean isMailableUser(Map u) {
    return u && u.accountId && u.active != false && u.accountType != 'app'
}

Map<String, String> getFieldIdsByName(List<String> names) {
    Map<String, String> result = [:]
    def resp = get('/rest/api/3/field').asObject(List)
    if (resp.status != 200) {
        logger.warn("Could not load fields: ${resp.status}")
        return result
    }
    names.each { n ->
        def f = (resp.body as List).find { it.name == n }   // first match, like DC .find
        if (f) result[n] = f.id as String
        else logger.warn("Field '${n}' not found")
    }
    logger.warn("Resolved field IDs: ${result}")
    return result
}

Map getLatestRecentComment(String issueKey, int minutesWindow, String tz) {
    // API v2 returns the raw text body (like DC getBody()); v3 returns ADF
    def resp = get("/rest/api/2/issue/${issueKey}/comment")
            .queryString('orderBy', '-created')
            .queryString('maxResults', 1)
            .asObject(Map)
    if (resp.status != 200) return [exists: false]
    List comments = (resp.body.comments ?: []) as List
    if (!comments) return [exists: false]

    Map latest = comments[0] as Map
    Date created = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(latest.created as String)
    long msWindow = minutesWindow * 60L * 1000L
    if ((System.currentTimeMillis() - created.time) > msWindow) return [exists: false]

    def fmt = new SimpleDateFormat("dd-MM-yyyy HH:mm")
    fmt.setTimeZone(TimeZone.getTimeZone(tz))
    return [exists: true, created: fmt.format(created), body: (latest.body ?: "") as String]
}

Map<String, Map> getAllAssignees(Map fields) {
    Map<String, Map> result = [:]
    if (fields.assignee?.accountId) result[fields.assignee.accountId as String] = fields.assignee as Map

    // Cloud link = outwardIssue (DC outward/destination) or inwardIssue (DC inward/source)
    List<String> linkedKeys = ((fields.issuelinks ?: []) as List)
            .collect { link -> (link.outwardIssue ?: link.inwardIssue)?.key as String }
            .findAll { it }
            .unique()

    linkedKeys.each { String k ->
        def r = get("/rest/api/3/issue/${k}").queryString('fields', 'assignee,status').asObject(Map)
        if (r.status != 200) {
            logger.warn("Skipping linked issue ${k}: ${r.status}")
            return
        }
        String st = r.body.fields?.status?.name as String
        def a = r.body.fields?.assignee
        logger.warn("  linked ${k}: status=${st}, assignee=${a?.displayName}")
        if (!(st in ['Cancelled', 'Canceled']) && a?.accountId) {
            result[a.accountId as String] = a as Map
        }
    }
    logger.warn("Assignees (issue + linked): ${result.values().collect { it.displayName }}")
    return result
}

Map<String, Map> getAllApproversFromFields(Map fields, Map<String, String> fieldIdsByName, List<String> names) {
    Map<String, Map> result = [:]
    names.each { n ->
        String id = fieldIdsByName[n]
        if (!id) return
        def v = fields[id]
        logger.warn("Field '${n}' (${id}) raw value: ${v}")
        if (v instanceof Collection) {
            v.each { if (it instanceof Map && it.accountId) result[it.accountId as String] = it as Map }
        } else if (v instanceof Map && v.accountId) {
            result[v.accountId as String] = v as Map
        }
    }
    logger.warn("Field approvers: ${result.values().collect { it.displayName }}")
    return result
}

Map<String, Map> getApproversFromApprovals(String issueKey) {
    // Replaces DC issue.approvals. Like the DC code, collects ALL approvers (not only approved ones).
    Map<String, Map> result = [:]
    int start = 0
    while (true) {
        def resp = get("/rest/servicedeskapi/request/${issueKey}/approval")
                .queryString('start', start)
                .queryString('limit', 50)
                .asObject(Map)
        if (resp.status != 200) {
            logger.warn("Approvals not available for ${issueKey}: ${resp.status}")
            break
        }
        ((resp.body.values ?: []) as List).each { approval ->
            logger.warn("Approval '${approval.name}' status=${approval.finalDecision}, approvers=${(approval.approvers ?: []).size()}")
            ((approval.approvers ?: []) as List).each { a ->
                def u = a.approver
                logger.warn("  approver: ${u?.displayName} (${u?.accountId}) decision=${a.approverDecision}")
                // To keep only users who approved, uncomment:
                // if (a.approverDecision != 'approved') return
                if (u?.accountId) result[u.accountId as String] = u as Map
            }
        }
        if (resp.body.isLastPage != false) break
        start += ((resp.body.size ?: 50) as int)
    }
    logger.warn("Approvals approvers for ${issueKey}: ${result.values().collect { it.displayName }}")
    return result
}

String resolveAccountIdByEmail(String email) {
    if (!email?.trim()) return null
    def resp = get('/rest/api/3/user/search').queryString('query', email.trim()).asObject(List)
    if (resp.status != 200 || !resp.body) return null
    List users = resp.body as List
    def exact = users.find { (it.emailAddress as String)?.equalsIgnoreCase(email.trim()) }
    if (exact) return exact.accountId as String
    return users.size() == 1 ? users[0].accountId as String : null
}

boolean sendNotification(String issueKey, String subject, String htmlBody, List<String> accountIds) {
    String textBody = htmlBody.replaceAll('(?i)<br\\s*/?>', '\n').replaceAll('<[^>]+>', '').trim()
    def resp = post("/rest/api/3/issue/${issueKey}/notify")
            .header('Content-Type', 'application/json')
            .body([
                subject : subject,
                htmlBody: htmlBody,
                textBody: textBody,
                to      : [
                    reporter: false,
                    assignee: false,
                    watchers: false,
                    voters  : false,
                    users   : accountIds.collect { [accountId: it] }
                ]
            ])
            .asString()
    if (!(resp.status in [200, 204])) {
        logger.warn("notify failed (${resp.status}): ${resp.body}")
        return false
    }
    return true
}
