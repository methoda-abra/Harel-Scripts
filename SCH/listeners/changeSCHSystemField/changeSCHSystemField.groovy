import Harel.utils.Helpers

final long SERVERS_ID = 18196L     // Servers
final long SYSTEMS_ID = 18264L     // מערכות השינוי
final String REF_ATTR = "systems"

// ---------- on update: run only if Servers changed ----------
def vars = binding.variables
if (vars.webhookEvent == "jira:issue_updated") {
    def items = (vars.changelog?.items ?: []) as List<Map>
    if (!items.any { it.fieldId == "customfield_${SERVERS_ID}".toString() }) {
        logger.info("Servers not changed – skipping")
        return
    }
}

// ---------- read Servers ----------
def servers = (Helpers.getFieldValue(issue.key, SERVERS_ID) ?: []) as List<Map>
logger.info("Servers value: ${servers}")

// ---------- collect referenced Systems (Assets API) ----------
String ws = servers ? servers[0].workspaceId : null
Set<String> systemIds = [] as Set
servers.each { Map s ->
    def resp = Unirest.get("https://api.atlassian.com/jsm/assets/workspace/${s.workspaceId}/v1/object/${s.objectId}/attributes")
            .basicAuth(ASSETS_USER_EMAIL, ASSETS_API_TOKEN)
            .header("Accept", "application/json")
            .asObject(List)
    assert resp.status == 200 : "Assets call failed for ${s.objectId}: ${resp.status} ${resp.body}"

    def attr = (resp.body as List<Map>).find { it.objectTypeAttribute?.name == REF_ATTR }
    attr?.objectAttributeValues?.each { v ->
        def id = v.referencedObject?.id
        if (id) systemIds << id.toString()
    }
}
logger.info("Systems found: ${systemIds}")

// ---------- set the target field ----------
def value = systemIds.collect { [workspaceId: ws, id: "${ws}:${it}".toString(), objectId: it] }
Helpers.setGenericFieldValue(logger, value, issue.key, SYSTEMS_ID)

logger.info("Systems after update: ${Helpers.getFieldValue(issue.key, SYSTEMS_ID)}")
