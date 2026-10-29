package com.poise.android

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's languages pick Poise's: the first one Poise has, else English. */
@RunWith(AndroidJUnit4::class)
class LanguageTest {
    private fun standby(languages: String): String {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(languages)) }
        return app.createConfigurationContext(config).getString(R.string.stage_standby)
    }

    @Test fun firstLanguagePoiseHas() = assertEquals("En espera", standby("de-DE,es-MX"))

    @Test fun englishWhenPoiseHasNone() = assertEquals("Standby", standby("de-DE,ja-JP"))

    @Test fun regionalVariants() {
        assertEquals("Em espera", standby("pt-PT"))
        assertEquals("جاهز", standby("ar-MA"))
    }
}
