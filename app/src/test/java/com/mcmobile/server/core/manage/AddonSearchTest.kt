package com.mcmobile.server.core.manage

import com.mcmobile.server.data.ServerType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AddonSearchTest {
    @Test fun searchAlwaysConstrainsGameLoaderServerAndProjectType() {
        val args = Addons.searchParameters("  中文 mod  ", ServerType.NEOFORGE, "1.21.1", 20, Addons.Sort.UPDATED)
        assertEquals("中文 mod", args["query"]); assertEquals("updated", args["index"]); assertEquals("20", args["offset"])
        val facets = Json.parseToJsonElement(args.getValue("facets")).jsonArray.map { it.jsonArray.single().jsonPrimitive.content }.toSet()
        assertEquals(setOf("project_type:mod", "categories:neoforge", "versions:1.21.1", "server_side!=unsupported"), facets)
    }
    @Test fun paginationUsesTotalRatherThanFullPageHeuristic() {
        val page = Addons.parseSearch(Json.parseToJsonElement("""{"hits":[{"project_id":"id","title":"Test","description":"desc","downloads":3}],"total_hits":22,"offset":20}""").jsonObject)
        assertTrue(page.hasNext)
        assertFalse(page.copy(total = 21).hasNext)
        assertEquals("id", page.hits.single().id)
    }
    @Test fun vanillaCannotSearchInstallableMods() {
        assertThrows(IllegalStateException::class.java) { Addons.searchParameters("", ServerType.VANILLA, "1.21.1", 0, Addons.Sort.RELEVANCE) }
    }
}
