import Harel.utils.Helpers

final long   SERVERS_ID       = 18196L   // Servers
final long   NOTIFY_ID        = 17447L   // גורמים לידוע
final String SYSTEMS_ATTR    = "systems"          // Server → systems
final String ROLE_USER_ATTR  = "user"             // Users System Roles → user
final String ACCOUNT_ID_ATTR = "jira_account_id"  // OD user object
final List<String> JOB_DESCRIPTIONS = [
        "גורם ליידוע על כל פעילויות / השבתות",
        "גורם מאשר השבתות"
]   // PMO-4475

// ---------- on update: run only if Servers changed ----------
def vars = binding.variables
if (vars.webhookEvent == "jira:issue_updated") {
    def items = (vars.changelog?.items ?: []) as List<Map>
    if (!items.any { it.fieldId == "customfield_${SERVERS_ID}".toString() }) {
        logger.info("Servers not changed – skipping")
        return
    }
}

final String ASSETS_BASE = "https://api.atlassian.com/jsm/assets/workspace"

def assetsGet = { String ws, String path ->
    def r = Unirest.get("${ASSETS_BASE}/${ws}/v1${path}")
            .basicAuth(ASSETS_USER_EMAIL, ASSETS_API_TOKEN)
            .header("Accept", "application/json")
            .asObject(List)
    assert r.status == 200 : "Assets GET ${path} failed: ${r.status} ${r.body}"
    r.body as List<Map>
}

// ---------- read Servers ----------
def servers = (Helpers.getFieldValue(issue.key, SERVERS_ID) ?: []) as List<Map>
logger.info("Servers value: ${servers}")
String ws = servers ? servers[0].workspaceId : null

// ---------- Servers → System keys ----------
Set<String> systemKeys = [] as Set
servers.each { Map s ->
    def attr = assetsGet(s.workspaceId, "/object/${s.objectId}/attributes")
            .find { it.objectTypeAttribute?.name == SYSTEMS_ATTR }
    attr?.objectAttributeValues?.each { v ->
        def key = v.referencedObject?.objectKey
        if (key) systemKeys << key.toString()
    }
}
logger.info("System keys: ${systemKeys}")

// ---------- AQL → user object ids ----------
Set<String> userObjectIds = [] as Set
if (systemKeys) {
    String jobs    = JOB_DESCRIPTIONS.collect { "\"${it}\"" }.join(", ")
    String systems = systemKeys.collect { "\"${it}\"" }.join(", ")
    String aql = """objectType = "user_system_roles" AND job_description IN (${jobs}) AND system IN (${systems})"""
    logger.info("AQL: ${aql}")

    int startAt = 0
    boolean isLast = false
    while (!isLast) {
        def resp = Unirest.post("${ASSETS_BASE}/${ws}/v1/object/aql")
                .queryString("startAt", startAt)
                .queryString("maxResults", 100)
                .queryString("includeAttributes", true)
                .basicAuth(ASSETS_USER_EMAIL, ASSETS_API_TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .body([qlQuery: aql])
                .asObject(Map)
        assert resp.status == 200 : "AQL failed: ${resp.status} ${resp.body}"

        def values = (resp.body.values ?: []) as List<Map>

        // resolve attribute name → id from the AQL response
        def userAttrId = (resp.body.objectTypeAttributes as List<Map>)
                ?.find { it.name == ROLE_USER_ATTR }?.id?.toString()
        if (!userAttrId) { logger.warn("Attribute '${ROLE_USER_ATTR}' not in AQL response"); return }

        values.each { role ->
            def userAttr = (role.attributes as List<Map>)
                    .find { it.objectTypeAttributeId?.toString() == userAttrId }
            userAttr?.objectAttributeValues?.each { v ->
                def id = v.referencedObject?.id
                if (id) userObjectIds << id.toString()
            }
        }
        startAt += values.size()
        isLast = resp.body.isLast == true || values.isEmpty()
    }
}
logger.info("User objects: ${userObjectIds}")

// ---------- user object → Jira Account ID (active only) ----------
Set<String> accountIds = [] as Set
userObjectIds.each { String oid ->
    def attr = assetsGet(ws, "/object/${oid}/attributes")
            .find { it.objectTypeAttribute?.name == ACCOUNT_ID_ATTR }
    String accountId = attr?.objectAttributeValues?.getAt(0)?.value?.toString()
    if (!accountId) { logger.warn("No Jira Account ID on user object ${oid}"); return }

    def u = Unirest.get("/rest/api/3/user").queryString("accountId", accountId).asObject(Map)
    if (u.status == 200 && u.body.active == true) accountIds << accountId
    else logger.warn("User ${accountId} (object ${oid}) inactive or not found: ${u.status}")
}
logger.info("Account IDs: ${accountIds}")

// ---------- set the user picker ----------
Helpers.setGenericFieldValue(logger, accountIds.collect { [accountId: it] }, issue.key, NOTIFY_ID)
logger.info("Notify field after update: ${Helpers.getFieldValue(issue.key, NOTIFY_ID)}")
