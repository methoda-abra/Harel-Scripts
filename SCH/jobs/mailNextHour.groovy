// ScriptRunner for Jira Cloud - Scheduled Job
// Sends "change starts soon" mail for Change/Request issues whose start date is within the next hour

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

// ================= CONFIG =================
@Field boolean TEST_MODE        = true
@Field String  TEST_ACCOUNT_ID  = "712020:72580e85-fcb5-408b-8fb6-3e67747d1e85"

@Field String CF_START_DATE     = "customfield_18059"   // תאריך תחילת עבודה
@Field String CF_WORK_PLAN      = "customfield_18262"   // תכנית עבודה (was customfield_19108 on DC)
@Field String CF_TEAM_APPROVERS = "customfield_18095"   // Involved Teams Approvers
@Field String CF_APP_EXECUTOR   = "customfield_18263"   // מבצע השינוי בצד האפליקטיבי

@Field String SENT_LABEL        = "next_hour_mail_sent"

@Field String ASSETS_MATCH_ATTR    = "jira_account_id"   // attribute in org_directory/users holding the Jira accountId
@Field String ASSETS_OBJECT_TYPE_ID = "854"             // org_directory (schema 464) -> users
// Script Variables used: ASSETS_WORKSPACE_ID, ASSETS_USER_EMAIL, ASSETS_API_TOKEN

@Field Map<String, Map> assetsCache = [:]
// ==========================================

String cfNum = CF_START_DATE.replace("customfield_", "")
String jql = """issuetype in (Change, Request) AND status = Approved
 AND cf[${cfNum}] >= now() AND cf[${cfNum}] <= "60m"
 AND (labels is EMPTY OR labels != ${SENT_LABEL})"""

// ---------- find issues ----------
List<String> keys = []
String pageToken = null
while (true) {
    Map body = [jql: jql, fields: ["key"], maxResults: 100]
    if (pageToken) body.nextPageToken = pageToken
    def resp = post("/rest/api/3/search/jql")
            .header("Content-Type", "application/json")
            .body(body)
            .asObject(Map)
    if (resp.status != 200) {
        logger.error("Search failed: ${resp.status} ${resp.body}")
        return
    }
    keys.addAll(resp.body.issues*.key)
    pageToken = resp.body.nextPageToken
    if (!pageToken) break
}

logger.info("Issues to notify: ${keys}")
keys.each { key ->
    try {
        processIssue(key)
    } catch (e) {
        logger.error("Failed on ${key}: ${e}")
    }
}

// =====================================================================

void processIssue(String key) {

    def r = get("/rest/api/2/issue/${key}")
            .queryString("fields", "summary,issuetype,reporter,issuelinks,attachment,${CF_WORK_PLAN},${CF_TEAM_APPROVERS},${CF_APP_EXECUTOR}")
            .queryString("expand", "renderedFields")
            .asObject(Map)
    if (r.status != 200) {
        logger.error("Cannot read ${key}: ${r.status}")
        return
    }

    Map issue = r.body as Map
    Map f = issue.fields as Map

    String summary = f.summary ?: "אין"
    String workPlan = (issue.renderedFields as Map)?.get(CF_WORK_PLAN) ?: "לא הוגדרה תכנית עבודה"
    logger.info("${key}: type=${f.issuetype?.name}, work plan ${(issue.renderedFields as Map)?.get(CF_WORK_PLAN) ? 'found' : 'EMPTY'}")
    boolean isRequest = f.issuetype?.name == "Request"

    // ---------- approvers (accountId -> displayName) ----------
    Map<String, String> approvers = [:]

    def ap = get("/rest/servicedeskapi/request/${key}/approval").asObject(Map)
    if (ap.status == 200) {
        ap.body.values?.each { a ->
            a.approvers?.each { x ->
                if (x.approver?.accountId) { approvers[x.approver.accountId] = x.approver.displayName; logger.info("${key}: approver [JSM approval] ${x.approver.displayName} (${x.approver.accountId})") }
            }
        }
    }

    def teamApprovers = f[CF_TEAM_APPROVERS]
    (teamApprovers instanceof List ? teamApprovers : [teamApprovers]).each { u ->
        if (u?.accountId) { approvers[u.accountId] = u.displayName; logger.info("${key}: approver [Involved Teams Approvers] ${u.displayName} (${u.accountId})") }
    }

    // ---------- executors ----------
    Map<String, String> executors = [:]

    List<String> linkedKeys = (f.issuelinks ?: []).collect { it.outwardIssue?.key ?: it.inwardIssue?.key }.findAll().unique()
    if (linkedKeys) {
        def lr = post("/rest/api/3/search/jql")
                .header("Content-Type", "application/json")
                .body([jql: "key in (${linkedKeys.join(',')})", fields: ["assignee", "labels", "status"], maxResults: 100])
                .asObject(Map)
        if (lr.status == 200) {
            lr.body.issues.each { li ->
                def lf = li.fields
                if (!(lf.status?.name in ["Cancelled", "Canceled"]) &&
                        ("involved_team" in (lf.labels ?: [])) &&
                        lf.assignee?.accountId) {
                    executors[lf.assignee.accountId] = lf.assignee.displayName
                    logger.info("${key}: executor [assignee of linked ${li.key}] ${lf.assignee.displayName} (${lf.assignee.accountId})")
                } else {
                    logger.info("${key}: skipped linked ${li.key} (status=${lf.status?.name}, involved_team=${'involved_team' in (lf.labels ?: [])}, assignee=${lf.assignee?.displayName})")
                }
            }
        }
    }

    if (f.reporter?.accountId) { executors[f.reporter.accountId] = f.reporter.displayName; logger.info("${key}: executor [reporter] ${f.reporter.displayName} (${f.reporter.accountId})") }

    if (isRequest && f[CF_APP_EXECUTOR]?.accountId) {
        executors[f[CF_APP_EXECUTOR].accountId] = f[CF_APP_EXECUTOR].displayName
        logger.info("${key}: executor [מבצע השינוי בצד האפליקטיבי] ${f[CF_APP_EXECUTOR].displayName} (${f[CF_APP_EXECUTOR].accountId})")
    }

    // ---------- table rows ----------
    String approversRows = approvers.collect { accId, dispName ->
        Map d = getUserFromAssets(accId)
        """
    <tr>
        <td style="padding:8px;border:1px solid #cccccc;">${d?.name ?: dispName}</td>
        <td style="padding:8px;border:1px solid #cccccc;">${d?.team ?: '-'}</td>
    </tr>"""
    }.join("")

    String performersRows = executors.collect { accId, dispName ->
        Map d = getUserFromAssets(accId)
        """
    <tr>
        <td style="padding:8px;border:1px solid #cccccc;">${d?.name ?: dispName}</td>
        <td style="padding:8px;border:1px solid #cccccc;">${d?.phone ?: '-'}</td>
        <td style="padding:8px;border:1px solid #cccccc;">${d?.team ?: '-'}</td>
    </tr>"""
    }.join("")

    // ---------- attachments as links (notify API cannot attach files) ----------
    String attachmentsHtml = ""
    if (f.attachment) {
        attachmentsHtml = "<br><br><b>קבצים מצורפים:</b><br>" +
                f.attachment.collect { "<a href=\"${it.content}\">${it.filename}</a>" }.join("<br>")
    }

    String subject = "שינוי ${summary} מתחיל בקרוב"

    String htmlBody = """
<table width="100%" dir="rtl" style="font-family:Arial;font-size:14px;">
<tr><td align="right">

<span style="font-size:20px;font-weight:bold;color:#0052cc;">
שימו לב - שינוי ${key} ${summary} מתחיל בקרוב
</span>

<br><br><b>להלן תכנית העבודה:</b><br><br>

<table width="100%" border="1" cellspacing="0" cellpadding="10" style="background:#f4f5f7;border-collapse:collapse;">
<tr><td>
<table width="100%" dir="rtl"><tr><td align="right" style="text-align:right;">
${workPlan}
</td></tr></table>
</td></tr>
</table>

<br><br><b>מאשרי השינוי:</b><br><br>

<table width="600" border="1" cellspacing="0" cellpadding="0" style="border-collapse:collapse;">
<tr style="background:#0052cc;color:white;">
<th style="padding:8px;">שם</th>
<th style="padding:8px;">יחידה</th>
</tr>
${approversRows}
</table>

<br><br><b>מבצעי השינוי:</b><br><br>

<table width="600" border="1" cellspacing="0" cellpadding="0" style="border-collapse:collapse;">
<tr style="background:#36b37e;color:white;">
<th style="padding:8px;">שם</th>
<th style="padding:8px;">טלפון</th>
<th style="padding:8px;">יחידה</th>
</tr>
${performersRows}
</table>
${attachmentsHtml}

<br><br>בהצלחה לכולם 😊<br><br>
בברכה<br>
צוות השינויים<br>
החטיבה הטכנולוגית

</td></tr>
</table>
"""

    // ---------- send ----------
    List<Map> recipients = TEST_MODE ?
            [[accountId: TEST_ACCOUNT_ID]] :
            executors.keySet().collect { [accountId: it] }

    if (!recipients) {
        logger.warn("${key}: no recipients")
        return
    }

    def nr = post("/rest/api/2/issue/${key}/notify")
            .header("Content-Type", "application/json")
            .body([
                    subject : TEST_MODE ? subject + " [TEST]" : subject,
                    textBody: "שינוי ${key} ${summary} מתחיל בקרוב",
                    htmlBody: htmlBody,
                    to      : [users: recipients, reporter: false, assignee: false, watchers: false, voters: false]
            ])
            .asString()

    if (nr.status != 204) {
        logger.error("${key}: notify failed ${nr.status} ${nr.body}")
        return
    }
    logger.info("${key}: mail sent to ${recipients*.accountId}")

    // ---------- mark as sent (skipped in test mode so you can re-run) ----------
    if (!TEST_MODE) {
        put("/rest/api/2/issue/${key}")
                .queryString("notifyUsers", false)
                .header("Content-Type", "application/json")
                .body([update: [labels: [[add: SENT_LABEL]]]])
                .asString()
    }
}

// ---------- Assets lookup by accountId ----------
Map getUserFromAssets(String accountId) {
    if (!accountId) return null
    if (assetsCache.containsKey(accountId)) return assetsCache[accountId]

    String base = "https://api.atlassian.com/jsm/assets/workspace/${ASSETS_WORKSPACE_ID}/v1"
    String auth = "Basic " + "${ASSETS_USER_EMAIL}:${ASSETS_API_TOKEN}".bytes.encodeBase64().toString()
    Map result = null
    try {
        String aql = """objectTypeId = ${ASSETS_OBJECT_TYPE_ID} AND "${ASSETS_MATCH_ATTR}" IN ("${accountId}")"""

        def sr = post("${base}/object/aql?maxResults=1")
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .body(JsonOutput.toJson([qlQuery: aql]))
                .asString()

        if (sr.status == 200) {
            def obj = new JsonSlurper().parseText(sr.body).values?.getAt(0)
            if (obj) {
                def ar = get("${base}/object/${obj.id}/attributes")
                        .header("Authorization", auth)
                        .header("Accept", "application/json")
                        .asString()
                List attrs = new JsonSlurper().parseText(ar.body) as List

                String team = attr(attrs, "Team") ?: attr(attrs, "Department") ?: attr(attrs, "Branch")
                result = [name: attr(attrs, "Employee Name"), phone: attr(attrs, "Phone Number"), team: team]
                logger.info("Assets: ${accountId} -> found ${obj.label} (name=${result.name}, phone=${result.phone ? 'yes' : 'no'}, team=${result.team})")
            } else {
                logger.warn("Assets: ${accountId} -> NOT FOUND (jira_account_id missing in org_directory/users)")
            }
        } else {
            logger.warn("Assets AQL failed for ${accountId}: ${sr.status} ${sr.body}")
        }
    } catch (e) {
        logger.warn("Assets lookup error for ${accountId}: ${e}")
    }

    assetsCache[accountId] = result
    return result
}

String attr(List attrs, String name) {
    // match ignoring case, spaces and underscores: "Employee Name" == "employee_name"
    String norm = name.toLowerCase().replaceAll(/[\s_]/, "")
    def a = attrs.find { it.objectTypeAttribute?.name?.toLowerCase()?.replaceAll(/[\s_]/, "") == norm }
    def v = a?.objectAttributeValues?.getAt(0)
    return (v?.referencedObject?.label ?: v?.displayValue ?: v?.value)?.toString()
}
