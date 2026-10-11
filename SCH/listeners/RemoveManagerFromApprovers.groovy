// ScriptRunner for Jira Cloud - Script Listener
// Event: Issue Updated   (no Condition needed - checks are in the script)
// Script Variables required: ASSETS_USER (email), ASSETS_TOKEN (API token)
//
// Assets structure:
//   System object -> attribute "system_manager"  (Object, reference to Person)
//   Person object -> attribute "jira_account_id" (Text, holds the accountId)

final String SYSTEMS_FIELD_ID   = "customfield_18264"   // מערכות השינוי
final String APPROVERS_FIELD    = "Approvers"
final String APPROVERS_FIELD_ID = "customfield_10003"                    // optional: set e.g. "customfield_10353" to skip name lookup
final String MANAGER_ATTR       = "system_manager"
final String ACCOUNT_ID_ATTR    = "jira_account_id"
final List<String> ISSUE_TYPES  = ["Change", "Sub Change"]

// ---- 0. Issue type check ----
def issueTypeName = issue.fields?.issuetype?.name
if (!(issueTypeName in ISSUE_TYPES)) return

// ---- 1. Only run when Systems changed ----
def systemsChange = changelog?.items?.find { it.fieldId == SYSTEMS_FIELD_ID }
if (!systemsChange) return

// "[ws:25878, ws:25950]" -> ["25878", "25950"]
def extractIds = { val ->
    if (!val) return []
    (val.toString() =~ /:(\d+)/).collect { it[1] as String }.unique()
}

def oldIds     = extractIds(systemsChange.from)
def newIds     = extractIds(systemsChange.to)
def removedIds = oldIds - newIds

logger.info("Systems old=${oldIds} new=${newIds} removed=${removedIds}")
if (removedIds.isEmpty()) return

// ---- 2. Assets API helpers ----
def workspaceId = (systemsChange.from.toString() =~ /([0-9a-f\-]{36}):/)[0][1]
def assetsBase  = "https://api.atlassian.com/jsm/assets/workspace/${workspaceId}/v1"

def assetsGet = { String path, Class type ->
    def resp = get("${assetsBase}${path}")
            .basicAuth(ASSETS_USER_EMAIL, ASSETS_API_TOKEN)
            .header("Accept", "application/json")
            .asObject(type)
    if (resp.status != 200) {
        logger.warn("Assets GET ${path} failed: ${resp.status} ${resp.body}")
        return null
    }
    resp.body
}

def findAttr = { List attrs, String name ->
    attrs?.find { it.objectTypeAttribute?.name?.equalsIgnoreCase(name) }
}

// cache Person objectId -> accountIds
Map<String, Set<String>> personCache = [:]

def getPersonAccountIds = { personId ->
    String pid = personId as String
    if (personCache.containsKey(pid)) return personCache[pid]

    def accAttr = findAttr(assetsGet("/object/${pid}/attributes", List) ?: [], ACCOUNT_ID_ATTR)
    Set<String> ids = (accAttr?.objectAttributeValues
            ?.collect { (it.value ?: it.searchValue) as String }
            ?.findAll { it } ?: []) as Set

    personCache[pid] = ids
    ids
}

// Returns [name: <system label>, managers: Set<accountId>]
def getSystemInfo = { String objectId ->
    def obj = assetsGet("/object/${objectId}", Map)
    if (!obj) return [name: objectId, managers: [] as Set]

    def managerAttr = findAttr(assetsGet("/object/${objectId}/attributes", List) ?: [], MANAGER_ATTR)

    Set<String> managers = [] as Set
    managerAttr?.objectAttributeValues?.each { v ->
        def refId = v.referencedObject?.id
        if (refId) managers.addAll(getPersonAccountIds(refId))
    }

    [name: obj.label ?: objectId, managers: managers]
}

// ---- 3. Calculate managers to remove ----
Set<String>  removedManagers    = [] as Set
Set<String>  currentManagers    = [] as Set
List<String> removedSystemNames = []

removedIds.each { id ->
    def info = getSystemInfo(id)
    removedSystemNames << info.name
    removedManagers.addAll(info.managers)
}

newIds.each { id ->
    currentManagers.addAll(getSystemInfo(id).managers)
}

def managersToRemove = removedManagers - currentManagers
logger.info("Removed managers=${removedManagers} current=${currentManagers} toRemove=${managersToRemove}")
if (managersToRemove.isEmpty()) return

// ---- 4. Get current Approvers ----
def approversId = APPROVERS_FIELD_ID ?: get("/rest/api/2/field").asObject(List).body
        .find { it.name == APPROVERS_FIELD }?.id

if (!approversId) {
    logger.error("Field '${APPROVERS_FIELD}' not found")
    return
}

def issueResp = get("/rest/api/2/issue/${issue.key}")
        .queryString("fields", approversId)
        .asObject(Map)

if (issueResp.status != 200 || !issueResp.body?.fields) {
    logger.error("Failed reading issue ${issue.key}: ${issueResp.status} ${issueResp.body}")
    return
}

List<Map> approvers = (issueResp.body.fields[approversId] ?: []) as List<Map>
logger.info("Approvers field ${approversId} = ${approvers*.accountId}")

def toRemove = approvers.findAll { managersToRemove.contains(it.accountId) }
if (toRemove.isEmpty()) return

def remaining            = approvers - toRemove
def removedApproverNames = toRemove.collect { it.displayName ?: it.accountId }

// ---- 5. Update issue ----
def upd = put("/rest/api/2/issue/${issue.key}")
        .queryString("notifyUsers", false)
        .header("Content-Type", "application/json")
        .body([fields: [(approversId): remaining.collect { [accountId: it.accountId] }]])
        .asString()

if (upd.status != 204) {
    logger.error("Failed updating Approvers: ${upd.status} ${upd.body}")
    return
}

logger.info("Removed systems: ${removedSystemNames.join(', ')} | Removed approvers: ${removedApproverNames.join(', ')}")

def cmt = post("/rest/api/2/issue/${issue.key}/comment")
        .header("Content-Type", "application/json")
        .body([body: "בעקבות הסרת המערכות ${removedSystemNames.join(', ')}, הוסרו מרשימת ה-Approvers מנהלי המערכת: ${removedApproverNames.join(', ')}."])
        .asString()

if (cmt.status != 201) {
    logger.warn("Failed adding comment: ${cmt.status} ${cmt.body}")
}
