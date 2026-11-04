package com.cj.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosTest {
    @Test fun callsForHelpStartSos() {
        for (said in listOf("help", "Help!", "help me", "CJ, help.", "Hey CJ help me please", "SOS", "S.O.S.", "s o s",
                            "Emergency!", "bachao", "madad karo", "help karo", "बचाओ", "मदद करो", "help me now",
                            // Heard on the glasses in testing, 4 Nov 2026:
                            "Help, help, help me. I'm dying. Help me, help, help.",
                            "help help", "I've fallen and I can't get up", "I fell down, please", "I can't breathe",
                            "call an ambulance", "I think I'm having a heart attack", "someone help me", "I'm hurt", "please bachao"))
            assertTrue(said, Sos.isHelp(said))
    }

    @Test fun everydayQuestionsDont() {
        for (said in listOf("help me write an email", "can you help", "how do I help my mom", "what is an emergency fund",
                            "play SOS by ABBA", "help me with my homework please", "emergency exit kahan hai", "",
                            "I'm dying to see that movie", "I fell asleep on the couch", "I'm dying of laughter",
                            "CJ bye", "the heart attack scene in that film was intense"))
            assertFalse(said, Sos.isHelp(said))
    }
}

class GlassesNameTest {
    @Test fun findsTheGlassesAmongHeadsets() {
        for (name in listOf("RB Meta 0A1B", "Ray-Ban Meta", "Rayban Stories", "Meta Ray-Ban Display")) assertTrue(name, GlassesMic.isGlasses(name))
        for (name in listOf("Bose Flex 2 SoundLink", "WH-1000XM4", "Galaxy Buds2", "Car Kit", "Metallica speaker")) assertFalse(name, GlassesMic.isGlasses(name))
    }
}
