package com.fitness.zwiftbike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GithubUpdaterTest {

    @Test
    fun parseReleaseJsonExtractsTagAndBothPhoneAndWatchApkAssets() {
        val sampleJson = """
            {
              "tag_name": "v1.1.0",
              "name": "LazyFitBike v1.1.0 (Phone + Galaxy Watch 4)",
              "published_at": "2026-10-28T08:00:00Z",
              "html_url": "https://github.com/richiesamlie/LazyFitBike/releases/tag/v1.1.0",
              "assets": [
                {
                  "name": "LazyFitBike-phone.apk",
                  "browser_download_url": "https://github.com/richiesamlie/LazyFitBike/releases/download/v1.1.0/LazyFitBike-phone.apk"
                },
                {
                  "name": "LazyFitBike-watch.apk",
                  "browser_download_url": "https://github.com/richiesamlie/LazyFitBike/releases/download/v1.1.0/LazyFitBike-watch.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = GithubUpdater.parseReleaseJson(sampleJson)
        assertNotNull(parsed)
        assertEquals("v1.1.0", parsed?.tagName)
        assertEquals(
            "https://github.com/richiesamlie/LazyFitBike/releases/download/v1.1.0/LazyFitBike-phone.apk",
            parsed?.phoneApkUrl
        )
        assertEquals(
            "https://github.com/richiesamlie/LazyFitBike/releases/download/v1.1.0/LazyFitBike-watch.apk",
            parsed?.watchApkUrl
        )
    }

    @Test
    fun parseReleaseJsonScopesAssetsToLatestReleaseInMultiReleaseArray() {
        val multiReleaseJson = """
            [
              {
                "tag_name": "v1.2.1",
                "name": "LazyFitBike v1.2.1",
                "assets": [
                  {
                    "browser_download_url": "https://github.com/richiesamlie/LazyFitBike/releases/download/v1.2.1/LazyFitBike-phone.apk"
                  }
                ]
              },
              {
                "tag_name": "v1.1.0",
                "name": "LazyFitBike v1.1.0",
                "assets": [
                  {
                    "browser_download_url": "https://github.com/richiesamlie/LazyFitBike/releases/download/v1.1.0/LazyFitBike-watch.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val parsed = GithubUpdater.parseReleaseJson(multiReleaseJson)
        assertNotNull(parsed)
        assertEquals("v1.2.1", parsed?.tagName)
        assertEquals(
            "https://github.com/richiesamlie/LazyFitBike/releases/download/v1.2.1/LazyFitBike-phone.apk",
            parsed?.phoneApkUrl
        )
        assertEquals(null, parsed?.watchApkUrl)
    }
}
