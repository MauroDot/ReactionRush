package com.maurointeractive.reactionrush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultsArtworkTest {
    @Test
    fun rankedMedalsMapExplicitlyToTheirOwnDistinctArtwork() {
        val expected = mapOf(
            PerformanceMedal.BRONZE to R.drawable.rr_medal_bronze,
            PerformanceMedal.SILVER to R.drawable.rr_medal_silver,
            PerformanceMedal.GOLD to R.drawable.rr_medal_gold,
            PerformanceMedal.PLATINUM to R.drawable.rr_medal_platinum,
            PerformanceMedal.ELITE to R.drawable.rr_medal_elite
        )
        expected.forEach { (medal, resource) -> assertEquals(medal.name, resource, medalArtwork(medal)) }
        assertEquals(5, expected.values.toSet().size)
        assertEquals(expected.keys, PerformanceMedal.entries.filter { medalArtwork(it) != null }.toSet())
    }

    @Test
    fun unrankedNeverBorrowsARewardImage() {
        assertNull(medalArtwork(PerformanceMedal.UNRANKED))
    }

    @Test
    fun existingReactionBoundariesSelectTheCorrespondingArtwork() {
        val expected = mapOf(
            1 to R.drawable.rr_medal_elite,
            199 to R.drawable.rr_medal_elite,
            200 to R.drawable.rr_medal_platinum,
            249 to R.drawable.rr_medal_platinum,
            250 to R.drawable.rr_medal_gold,
            299 to R.drawable.rr_medal_gold,
            300 to R.drawable.rr_medal_silver,
            349 to R.drawable.rr_medal_silver,
            350 to R.drawable.rr_medal_bronze,
            449 to R.drawable.rr_medal_bronze,
            450 to null,
            Int.MAX_VALUE to null
        )
        expected.forEach { (score, resource) ->
            assertEquals("Reaction $score ms", resource, medalArtwork(PlayerProgression.medal(score)))
        }
    }

    @Test
    fun allModesKeepTheirGameArtworkExceptVerifiedSessionResults() {
        GameMode.entries.forEach { mode ->
            GameState.entries.forEach { state ->
                listOf(false, true).forEach { verified ->
                    val expected = if (state == GameState.SESSION_RESULT && verified) {
                        ArcadeBackdrop.RESULTS
                    } else {
                        gameBackdrop(mode)
                    }
                    assertEquals("$mode / $state / verified=$verified", expected,
                        sessionBackdrop(mode, state, verified))
                }
            }
        }
    }

    @Test
    fun completedSessionAverageRemainsTheMedalSourceForEveryMode() {
        val cases = listOf(
            Triple(GameMode.QUICK_DRAW, listOf(199), R.drawable.rr_medal_elite),
            Triple(GameMode.CLASSIC_RUSH, listOf(190, 300, 300, 350, 360), R.drawable.rr_medal_silver),
            Triple(GameMode.ENDURANCE_RUSH, listOf(150) + List(9) { 400 }, R.drawable.rr_medal_bronze)
        )
        cases.forEach { (mode, scores, resource) ->
            val actualMedal = PlayerProgression.medal(PlayerProgression.sessionScore(mode, scores))
            assertEquals(mode.title, resource, medalArtwork(actualMedal))
        }
    }
}
