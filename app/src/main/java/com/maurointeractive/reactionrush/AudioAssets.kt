package com.maurointeractive.reactionrush

/** Exact approved playback bytes. Original WAV music masters are intentionally not packaged. */
data class AudioAsset(
    val resourceId: Int,
    val fileName: String,
    val sourcePath: String,
    val sha256: String,
    val durationMillis: Long
)

object AudioAssets {
    val music = mapOf(
        MusicTrack.MENU to AudioAsset(R.raw.rr_bgm_menu, "rr_bgm_menu.ogg", "music_ogg/rr_bgm_menu.ogg", "1fe43c0064b1c84a8996acb3c19e8eba06c4862e835f1876f51856a818cca764", 65500L),
        MusicTrack.QUICK_DRAW to AudioAsset(R.raw.rr_bgm_quickdraw, "rr_bgm_quickdraw.ogg", "music_ogg/rr_bgm_quickdraw.ogg", "22ea251e89a5614682e8d21758e705bdf58de109599f4c75bbdd2c3d34f2ad35", 61282L),
        MusicTrack.CLASSIC to AudioAsset(R.raw.rr_bgm_classic, "rr_bgm_classic.ogg", "music_ogg/rr_bgm_classic.ogg", "fcbec73387559be0770d38b25ea26adac9a9bef3430781224fdb8f2a2b81f2bd", 57360L),
        MusicTrack.ENDURANCE to AudioAsset(R.raw.rr_bgm_endurance, "rr_bgm_endurance.ogg", "music_ogg/rr_bgm_endurance.ogg", "a9f87dc5caa751f290794ec1319d5dea709fb10427e84ba75ec38a1ebda7e4b5", 82160L),
        MusicTrack.RESULTS to AudioAsset(R.raw.rr_bgm_results, "rr_bgm_results.ogg", "music_ogg/rr_bgm_results.ogg", "302be91e1bcce0d050f10153efbc1e115ebcf48bc3bd4ae020b8ec4af4f1700a", 37960L)
    )

    val effects = mapOf(
        SoundEffect.ACHIEVEMENT_UNLOCK to AudioAsset(R.raw.rr_sfx_achievement_unlock, "rr_sfx_achievement_unlock.wav", "sfx_wav/rr_sfx_achievement_unlock.wav", "f2c49ab72a117213fec2bc4a562339ea30961ad3d7699209b98011fecf445d5f", 760L),
        SoundEffect.BUTTON_SELECT to AudioAsset(R.raw.rr_sfx_button_select, "rr_sfx_button_select.wav", "sfx_wav/rr_sfx_button_select.wav", "a7321a6ab3d96ebcfa5d31c9e0b14faabf53862d561ae5407854efefe7c6ff61", 160L),
        SoundEffect.FALSE_START to AudioAsset(R.raw.rr_sfx_false_start, "rr_sfx_false_start.wav", "sfx_wav/rr_sfx_false_start.wav", "e79d7e54d2f0ac3393540f704d438cca243fed727d1dcc085668f641c4a3b976", 320L),
        SoundEffect.LEVEL_UP to AudioAsset(R.raw.rr_sfx_level_up, "rr_sfx_level_up.wav", "sfx_wav/rr_sfx_level_up.wav", "419297bafc343560cfa2a01deb8bc66759a8818147d0189cd7c7fbc5ba355e5f", 950L),
        SoundEffect.MEDAL_AWARDED to AudioAsset(R.raw.rr_sfx_medal_awarded, "rr_sfx_medal_awarded.wav", "sfx_wav/rr_sfx_medal_awarded.wav", "d3d9ad826a1d1c8bbda265c8b86cece39759636dced8e8416220b2c49a054247", 480L),
        SoundEffect.MENU_NAVIGATE to AudioAsset(R.raw.rr_sfx_menu_navigate, "rr_sfx_menu_navigate.wav", "sfx_wav/rr_sfx_menu_navigate.wav", "14ea1ba67cdedeb4c28b06a576eb3c9ca5c55887ebf2bb85048e3694580a3833", 85L),
        SoundEffect.NEW_RECORD to AudioAsset(R.raw.rr_sfx_new_record, "rr_sfx_new_record.wav", "sfx_wav/rr_sfx_new_record.wav", "0aa3020bb42a79ceb025ab4851125823b6b6c34e31fe5af763c30188e6c5c6f4", 620L),
        SoundEffect.REACTION_SUCCESS to AudioAsset(R.raw.rr_sfx_reaction_success, "rr_sfx_reaction_success.wav", "sfx_wav/rr_sfx_reaction_success.wav", "7812895f150a29d282b6b2bb264d4c0e574983d8ca9febf2564e86d9d4c2e93e", 170L),
        SoundEffect.ROUND_PREPARE to AudioAsset(R.raw.rr_sfx_round_prepare, "rr_sfx_round_prepare.wav", "sfx_wav/rr_sfx_round_prepare.wav", "ce206469f671ee7ef2b735364ccfd58f34b38f3fba6d5ae9c2cd7cccc61b9b6a", 290L)
    )

    val stingers = mapOf(
        CelebrationStinger.ACHIEVEMENT to AudioAsset(R.raw.rr_stinger_achievement_v1, "rr_stinger_achievement_v1.wav", "stingers_wav/rr_stinger_achievement_v1.wav", "e693aac94eab30140617107a5efa17a8a06dcc3c872c34410f55ddf8099f08ea", 2100L),
        CelebrationStinger.LEVEL_UP to AudioAsset(R.raw.rr_stinger_level_up_v1, "rr_stinger_level_up_v1.wav", "stingers_wav/rr_stinger_level_up_v1.wav", "12e1fade4fac2666cb03ef022adb2890ef08b14290adfb561486d49de8e38a53", 3100L),
        CelebrationStinger.MILESTONE to AudioAsset(R.raw.rr_stinger_milestone_v1, "rr_stinger_milestone_v1.wav", "stingers_wav/rr_stinger_milestone_v1.wav", "69db6f092b83a92c62ae72067e6cb9d436b85c05044fd4cea73d70b7a53849c7", 1700L),
        CelebrationStinger.PERSONAL_RECORD to AudioAsset(R.raw.rr_stinger_personal_record_v1, "rr_stinger_personal_record_v1.wav", "stingers_wav/rr_stinger_personal_record_v1.wav", "84189631b70a0194c9c2e8abd1f1ad1a9e1b5f65b6bb374ebe30e16359278d65", 2400L),
        CelebrationStinger.VICTORY to AudioAsset(R.raw.rr_stinger_victory_v1, "rr_stinger_victory_v1.wav", "stingers_wav/rr_stinger_victory_v1.wav", "7a854f7e6cbee3f0a84211517740f05a5a55ed10fe1205ef69abf3ffa8baf6d8", 3500L)
    )

    /** Reserved asset only. Visual-only competition has no GO audio event or playable mapping. */
    val reservedGoSignal = AudioAsset(R.raw.rr_sfx_go_signal, "rr_sfx_go_signal.wav", "sfx_wav/rr_sfx_go_signal.wav", "040e371a5d695fe5d096bef2379bb9c321d6d3121825085678355751ffa738fc", 110L)

    val all: List<AudioAsset> = music.values + effects.values + stingers.values + reservedGoSignal
}
