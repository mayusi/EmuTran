package io.github.mayusi.emutran.ui.common

/**
 * Single source of truth for the EmuTran community / support Discord invite.
 *
 * Every surface that points users at Discord — the first-launch prompt, the
 * About screen row, and the Dashboard "need help?" entry — references this
 * constant. Never hard-code the invite URL anywhere else: if the invite ever
 * rotates, this is the one line to change.
 */
const val DISCORD_INVITE_URL = "https://discord.gg/jEnMYW5YfE"
