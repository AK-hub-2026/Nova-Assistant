package com.example.nova.ai

import com.example.nova.core.ExecutionChannel
import com.example.nova.core.PlannedToolCall
import com.example.nova.core.RiskLevel

/**
 * Zero-RAM, zero-network deterministic intent parser for direct Android system,
 * Accessibility, and memory commands. Runs in <2ms on low-end devices.
 */
object LocalDeterministicParser {

    private val compoundSplitRegex = Regex(
        """\s+(?:and\s+then|and\s+also|then\s+also|and|then|also)\s+|\s*[;]\s*""",
        RegexOption.IGNORE_CASE
    )

    private val leadingVerbRegex = Regex(
        """^(show|open|check|get|read|launch|start|turn\s+on|turn\s+off)\s+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * M7 — Parses either a single deterministic command or a compound multi-task command
     * (e.g., "Show my photos and device information.") into an ordered list of real [PlannedToolCall]s.
     * Never creates tasks that were not explicitly requested in [rawInput].
     */
    fun tryParseMulti(rawInput: String): List<PlannedToolCall> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return emptyList()

        // Only split into multiple tasks when no quoted literal spans across the connector
        val hasUnbalancedOrCompoundQuotes = trimmed.count { it == '"' || it == '\'' } >= 2 &&
            (trimmed.lowercase().startsWith("type ") ||
                trimmed.lowercase().startsWith("enter ") ||
                trimmed.lowercase().startsWith("text ") ||
                trimmed.lowercase().startsWith("send sms") ||
                trimmed.lowercase().startsWith("remember ") ||
                trimmed.lowercase().startsWith("remind "))

        if (!hasUnbalancedOrCompoundQuotes && compoundSplitRegex.containsMatchIn(trimmed)) {
            val rawClauses = trimmed.split(compoundSplitRegex)
                .map { it.trim().trimEnd('.', '!', '?').trim() }
                .filter { it.isNotBlank() }

            if (rawClauses.size >= 2) {
                val firstVerb = leadingVerbRegex.find(rawClauses.first())?.groupValues?.getOrNull(1)
                val parsedCalls = mutableListOf<PlannedToolCall>()
                var allMatched = true

                for ((index, clause) in rawClauses.withIndex()) {
                    val direct = tryParseSingle(clause)
                    val resolved = if (direct != null) {
                        direct
                    } else if (index > 0 && !firstVerb.isNullOrBlank()) {
                        tryParseSingle("$firstVerb $clause")
                    } else {
                        null
                    }
                    if (resolved == null) {
                        allMatched = false
                        break
                    }
                    parsedCalls.add(resolved)
                }

                if (allMatched && parsedCalls.size >= 2) {
                    return parsedCalls
                }
            }
        }

        return listOfNotNull(tryParseSingle(trimmed))
    }

    fun tryParse(rawInput: String): PlannedToolCall? {
        return tryParseMulti(rawInput).firstOrNull()
    }

    private fun tryParseSingle(rawInput: String): PlannedToolCall? {
        val text = rawInput.trim().trimEnd('.', '!', '?').trim()
        if (text.isBlank()) return null
        val lower = text.lowercase()

        // 00. Fast local conversational queries & greetings (M11-B & M11-C: <1ms turnaround)
        val greetingCommands = setOf(
            "hello", "hi", "hey", "hello arya", "hi arya", "hey arya",
            "hello boss", "hey boss", "namaste", "namaste arya", "namaste boss",
            "good morning", "good afternoon", "good evening"
        )
        if (lower in greetingCommands) {
            return PlannedToolCall(
                toolName = "none",
                arguments = emptyMap(),
                reasoning = "Matched fast local conversational greeting.",
                expectedOutcomeDescription = "Immediate conversational greeting response",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.DIRECT_RESPONSE,
                spokenResponseDraft = "Hello Boss, आज क्या करें? बताइए, मैं क्या मदद करूँ?"
            )
        }

        val identityCommands = setOf(
            "who are you", "what is your name", "what's your name",
            "tell me about yourself", "who made you", "what can you do", "help", "kya kar sakti ho"
        )
        if (lower in identityCommands) {
            return PlannedToolCall(
                toolName = "none",
                arguments = emptyMap(),
                reasoning = "Matched fast local assistant identity command.",
                expectedOutcomeDescription = "Immediate Arya identity description",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.DIRECT_RESPONSE,
                spokenResponseDraft = "मैं आर्या हूँ (Arya), your personal AI assistant. I can open apps, adjust settings, inspect your screen, manage tasks, and help with anything you need. बताइए Boss, आज क्या करें?"
            )
        }

        val conversationalStatusCommands = setOf(
            "how are you", "how are you doing", "kaise ho", "kya haal hai"
        )
        if (lower in conversationalStatusCommands) {
            return PlannedToolCall(
                toolName = "none",
                arguments = emptyMap(),
                reasoning = "Matched fast local status query.",
                expectedOutcomeDescription = "Immediate assistant status response",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.DIRECT_RESPONSE,
                spokenResponseDraft = "मैं बिल्कुल ठीक हूँ Boss, thank you! आप बताइए, आज क्या करना है?"
            )
        }

        val thanksCommands = setOf(
            "thank you", "thanks", "thanks arya", "thank you arya", "shukriya", "dhanyawad"
        )
        if (lower in thanksCommands) {
            return PlannedToolCall(
                toolName = "none",
                arguments = emptyMap(),
                reasoning = "Matched fast local thanks query.",
                expectedOutcomeDescription = "Immediate polite acknowledgement",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.DIRECT_RESPONSE,
                spokenResponseDraft = "You're very welcome, Boss! Always here for you."
            )
        }

        // 0. Real Device Information Workspace (M4: query_device_info)
        if (lower == "query_device_info" ||
            lower == "device info" ||
            lower == "device information" ||
            lower == "device status" ||
            lower == "phone info" ||
            lower == "phone status" ||
            lower == "system status" ||
            lower == "system info" ||
            lower.contains("device info") ||
            lower.contains("battery level") ||
            lower.contains("battery status") ||
            lower.contains("battery percentage") ||
            lower.contains("how much ram") ||
            lower.contains("available ram") ||
            lower.contains("memory status") ||
            lower.contains("storage space") ||
            lower.contains("available storage") ||
            lower.contains("storage status") ||
            lower.contains("android version") ||
            lower.contains("what device is this") ||
            lower.contains("network status")
        ) {
            return PlannedToolCall(
                toolName = "query_device_info",
                arguments = emptyMap(),
                reasoning = "Matched local device information query command.",
                expectedOutcomeDescription = "Real Android battery, RAM, storage, OS, and network state queried",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "बताइए Boss, checking your live device information."
            )
        }

        // 0b. Real Photos / Media Access Workspace (M5: query_photos)
        val photoPickerExplicitCommands = setOf(
            "pick photos",
            "pick a photo",
            "select photos",
            "choose photos",
            "open photo picker",
            "launch photo picker"
        )
        val photoMediaStoreCommands = setOf(
            "query_photos",
            "show my photos",
            "open my photos",
            "show photos",
            "my photos",
            "show recent photos",
            "open recent photos",
            "open my recent photos",
            "recent photos",
            "show recent pictures",
            "open recent pictures",
            "open my recent pictures",
            "show my pictures",
            "recent pictures",
            "show photos from my gallery",
            "open photos from my gallery",
            "show my gallery",
            "show gallery"
        )
        if (lower in photoPickerExplicitCommands || lower in photoMediaStoreCommands) {
            val mode = if (lower in photoPickerExplicitCommands) "picker" else "mediastore"
            return PlannedToolCall(
                toolName = "query_photos",
                arguments = mapOf("mode" to mode),
                reasoning = "Matched local photo workspace command ($mode).",
                expectedOutcomeDescription = "Real accessible device photos queried via MediaStore or Android Photo Picker",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = if (mode == "picker") {
                    "Opening photo selection workspace."
                } else {
                    "Checking your accessible photos."
                }
            )
        }

        // 1. Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOff = lower.contains("off") || lower.contains("disable")
            return PlannedToolCall(
                toolName = "set_flashlight",
                arguments = mapOf("enabled" to (!turnOff).toString()),
                reasoning = "Matched local hardware flashlight command.",
                expectedOutcomeDescription = "CameraManager torch state becomes ${!turnOff}",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = if (turnOff) "Turning flashlight off." else "Turning flashlight on."
            )
        }

        // 2. Volume control
        if (lower.contains("volume") || lower == "mute" || lower == "unmute") {
            val stream = when {
                lower.contains("ring") -> "ring"
                lower.contains("alarm") -> "alarm"
                lower.contains("notif") -> "notification"
                else -> "music"
            }
            val pctMatch = Regex("""(\d{1,3})\s*%?""").find(lower)
            val direction = when {
                lower.contains("mute") -> "mute"
                lower.contains("max") || lower.contains("full") -> "max"
                pctMatch != null -> "${pctMatch.groupValues[1]}%"
                lower.contains("down") || lower.contains("lower") || lower.contains("decrease") -> "down"
                else -> "up"
            }
            return PlannedToolCall(
                toolName = "set_volume",
                arguments = mapOf("stream" to stream, "level" to direction),
                reasoning = "Matched local AudioManager volume command.",
                expectedOutcomeDescription = "AudioManager $stream volume adjusted ($direction)",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "Adjusting $stream volume."
            )
        }

        // 3. System Settings panels
        if (lower.startsWith("open ") && lower.contains("setting")) {
            val section = lower.removePrefix("open ").replace("settings", "").replace("setting", "").trim()
            return PlannedToolCall(
                toolName = "open_settings",
                arguments = mapOf("section" to section.ifBlank { "system" }),
                reasoning = "Matched local Android Settings intent command.",
                expectedOutcomeDescription = "Android Settings ($section) opens",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "Opening ${section.ifBlank { "system" }} settings."
            )
        }

        // 4. Set Alarm
        val alarmMatch = Regex("""(?i)(?:set\s+(?:an?\s+)?alarm\s+(?:for\s+)?)(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(text)
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toIntOrNull() ?: 8
            val minute = alarmMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = alarmMatch.groupValues[3].lowercase()
            if (ampm == "pm" && hour in 1..11) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return PlannedToolCall(
                toolName = "set_alarm",
                arguments = mapOf(
                    "hour" to hour.toString(),
                    "minute" to minute.toString(),
                    "label" to "Nova Alarm"
                ),
                reasoning = "Matched local AlarmClock.ACTION_SET_ALARM pattern.",
                expectedOutcomeDescription = "Clock alarm set for $hour:${String.format("%02d", minute)}",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "Setting an alarm for $hour:${String.format("%02d", minute)}."
            )
        }

        // 5. Set Timer
        val timerMatch = Regex("""(?i)set\s+(?:a\s+)?timer\s+for\s+(\d+)\s*(second|seconds|sec|secs|minute|minutes|min|mins)""").find(text)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toIntOrNull() ?: 1
            val unit = timerMatch.groupValues[2].lowercase()
            val seconds = if (unit.startsWith("min")) amount * 60 else amount
            return PlannedToolCall(
                toolName = "set_timer",
                arguments = mapOf("seconds" to seconds.toString(), "label" to "Nova Timer"),
                reasoning = "Matched local AlarmClock.ACTION_SET_TIMER pattern.",
                expectedOutcomeDescription = "System timer started for ${seconds}s",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "Starting a timer for $amount $unit."
            )
        }

        // 6. Schedule Reminder
        val reminderMatch = Regex("""(?i)remind\s+me\s+in\s+(\d+)\s*(?:minute|minutes|min|mins)\s+to\s+(.+)""").find(text)
        if (reminderMatch != null) {
            val mins = reminderMatch.groupValues[1]
            val title = reminderMatch.groupValues[2].trim()
            return PlannedToolCall(
                toolName = "schedule_reminder",
                arguments = mapOf("minutes" to mins, "title" to title),
                reasoning = "Matched local reminder scheduling command.",
                expectedOutcomeDescription = "Reminder saved and scheduled in AlarmManager",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.LOCAL_MEMORY,
                spokenResponseDraft = "Scheduling a reminder to $title in $mins minutes."
            )
        }

        // 7. Save Memory Fact
        val rememberIso = Regex("""(?i)^(?:remember\s+that|save\s+memory|note\s+that)\s+(.+?)\s+(?:is|=|:)\s+(.+)$""").find(text)
        if (rememberIso != null) {
            return PlannedToolCall(
                toolName = "save_memory",
                arguments = mapOf(
                    "key" to rememberIso.groupValues[1].trim(),
                    "value" to rememberIso.groupValues[2].trim(),
                    "category" to "PREFERENCE"
                ),
                reasoning = "Matched local key-value memory command.",
                expectedOutcomeDescription = "Fact stored in Room memory_facts table",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.LOCAL_MEMORY,
                spokenResponseDraft = "Saving that to your local memory."
            )
        }
        if (lower.startsWith("remember ") || lower.startsWith("save note ")) {
            val note = text.replaceFirst(Regex("(?i)^(remember|save note)\\s+"), "").trim()
            if (note.isNotBlank()) {
                return PlannedToolCall(
                    toolName = "save_memory",
                    arguments = mapOf("key" to "User Note", "value" to note, "category" to "USER_NOTE"),
                    reasoning = "Matched local note memory command.",
                    expectedOutcomeDescription = "Note persisted in Room database",
                    riskLevel = RiskLevel.SAFE,
                    channel = ExecutionChannel.LOCAL_MEMORY,
                    spokenResponseDraft = "Saved note to Nova Memory."
                )
            }
        }

        // 8. Dial Phone Number (Sensitive -> Requires Confirmation)
        val dialMatch = Regex("""(?i)^(?:call|dial)\s+([+\d][\d\s\-()]{2,})$""").find(text)
        if (dialMatch != null) {
            val number = dialMatch.groupValues[1].trim()
            return PlannedToolCall(
                toolName = "dial_number",
                arguments = mapOf("phone" to number),
                reasoning = "Matched phone dial command.",
                expectedOutcomeDescription = "System dialer opens with $number",
                riskLevel = RiskLevel.SENSITIVE_CONFIRM,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "Opening phone dialer for $number."
            )
        }

        // 9. Compose SMS (Sensitive -> Requires Confirmation)
        val smsMatch = Regex("""(?i)^(?:send\s+sms\s+to|text)\s+([+\d][\d\s\-()]{2,})\s+(?:saying\s+)?(.+)$""").find(text)
        if (smsMatch != null) {
            val phone = smsMatch.groupValues[1].trim()
            val body = smsMatch.groupValues[2].trim()
            return PlannedToolCall(
                toolName = "compose_sms",
                arguments = mapOf("phone" to phone, "message" to body),
                reasoning = "Matched SMS composition command.",
                expectedOutcomeDescription = "SMS app opens with recipient $phone and message body",
                riskLevel = RiskLevel.SENSITIVE_CONFIRM,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "Preparing SMS draft to $phone."
            )
        }

        // 10. Read / Inspect Screen or Accessibility Status (M6)
        val normalizedQuestion = lower.removeSuffix("?").trim()
        if (normalizedQuestion == "read screen" ||
            normalizedQuestion == "read the screen" ||
            normalizedQuestion == "read my screen" ||
            normalizedQuestion == "inspect screen" ||
            normalizedQuestion == "what is on my screen" ||
            normalizedQuestion == "what's on my screen" ||
            normalizedQuestion == "whats on my screen"
        ) {
            return PlannedToolCall(
                toolName = "read_screen",
                arguments = emptyMap(),
                reasoning = "Matched screen inspection command.",
                expectedOutcomeDescription = "Semantic UI node tree extracted from active window",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Reading active screen elements."
            )
        }

        if (normalizedQuestion == "show accessibility status" ||
            normalizedQuestion == "accessibility status" ||
            normalizedQuestion == "check accessibility" ||
            normalizedQuestion == "check accessibility status" ||
            normalizedQuestion == "accessibility workspace" ||
            normalizedQuestion == "show accessibility workspace" ||
            normalizedQuestion == "query_accessibility_status"
        ) {
            return PlannedToolCall(
                toolName = "query_accessibility_status",
                arguments = emptyMap(),
                reasoning = "Matched Accessibility status inspection command.",
                expectedOutcomeDescription = "Real AccessibilityService connection and active window status queried",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Checking Accessibility service and active window status."
            )
        }

        // 11. Visual Fallback Screenshot Extension Point
        if (lower == "take screenshot" || lower == "visual inspect" || lower == "inspect pixels") {
            return PlannedToolCall(
                toolName = "capture_visual_fallback",
                arguments = emptyMap(),
                reasoning = "Matched on-demand visual screenshot fallback command.",
                expectedOutcomeDescription = "ScreenUnderstandingFallback status reported",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Checking screen understanding fallback status."
            )
        }

        // 12. Global Accessibility Navigation
        if (lower in listOf("go back", "navigate back", "press back", "back")) {
            return PlannedToolCall(
                toolName = "navigate_global",
                arguments = mapOf("action" to "back"),
                reasoning = "Matched Accessibility global BACK navigation.",
                expectedOutcomeDescription = "GLOBAL_ACTION_BACK dispatched",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Going back."
            )
        }
        if (lower in listOf("go home", "home screen", "press home")) {
            return PlannedToolCall(
                toolName = "navigate_global",
                arguments = mapOf("action" to "home"),
                reasoning = "Matched Accessibility global HOME navigation.",
                expectedOutcomeDescription = "GLOBAL_ACTION_HOME dispatched",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Going to the home screen."
            )
        }
        if (lower in listOf("open recents", "recent apps", "show recents")) {
            return PlannedToolCall(
                toolName = "navigate_global",
                arguments = mapOf("action" to "recents"),
                reasoning = "Matched Accessibility global RECENTS navigation.",
                expectedOutcomeDescription = "GLOBAL_ACTION_RECENTS dispatched",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Opening recent apps."
            )
        }
        if (lower in listOf("open notifications", "show notifications", "notification shade")) {
            return PlannedToolCall(
                toolName = "navigate_global",
                arguments = mapOf("action" to "notifications"),
                reasoning = "Matched Accessibility global NOTIFICATIONS shade command.",
                expectedOutcomeDescription = "GLOBAL_ACTION_NOTIFICATIONS dispatched",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Opening notification shade."
            )
        }

        // 13. Accessibility Scroll
        if (lower.startsWith("scroll ")) {
            val dir = lower.removePrefix("scroll ").trim().ifBlank { "down" }
            return PlannedToolCall(
                toolName = "scroll_screen",
                arguments = mapOf("direction" to dir),
                reasoning = "Matched Accessibility scroll command.",
                expectedOutcomeDescription = "Active scrollable container scrolls $dir",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Scrolling $dir."
            )
        }

        // 14. Accessibility Long-Click / Long-Press
        if (lower.startsWith("long click ") || lower.startsWith("long press ") || lower.startsWith("long-press ")) {
            val target = text.replaceFirst(Regex("(?i)^(long\\s+click|long\\s+press|long-press)\\s+(on\\s+)?"), "").trim()
            if (target.isNotBlank()) {
                val isSensitive = isConsequentialAccessibilityTarget(target)
                return PlannedToolCall(
                    toolName = "click_node",
                    arguments = mapOf("target" to target, "action" to "LONG_CLICK"),
                    reasoning = "Matched Accessibility semantic long-click command for '$target'.",
                    expectedOutcomeDescription = "UI node matching '$target' receives ACTION_LONG_CLICK and state change is verified",
                    riskLevel = if (isSensitive) RiskLevel.SENSITIVE_CONFIRM else RiskLevel.SAFE,
                    channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                    spokenResponseDraft = "Long-pressing $target."
                )
            }
        }

        // 14b. Accessibility Click / Press / Tap
        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ")) {
            val target = text.replaceFirst(Regex("(?i)^(click|tap|press)\\s+(on\\s+)?"), "").trim()
            if (target.isNotBlank()) {
                val isSensitive = isConsequentialAccessibilityTarget(target)
                return PlannedToolCall(
                    toolName = "click_node",
                    arguments = mapOf("target" to target, "action" to "CLICK"),
                    reasoning = "Matched Accessibility semantic click command for '$target'.",
                    expectedOutcomeDescription = "UI node matching '$target' receives ACTION_CLICK and state change is verified",
                    riskLevel = if (isSensitive) RiskLevel.SENSITIVE_CONFIRM else RiskLevel.SAFE,
                    channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                    spokenResponseDraft = "Clicking $target."
                )
            }
        }

        // 14c. Accessibility Focus / Select
        if (lower.startsWith("focus ")) {
            val target = text.replaceFirst(Regex("(?i)^focus\\s+(on\\s+)?"), "").trim()
            if (target.isNotBlank()) {
                return PlannedToolCall(
                    toolName = "focus_node",
                    arguments = mapOf("target" to target),
                    reasoning = "Matched Accessibility semantic focus command for '$target'.",
                    expectedOutcomeDescription = "UI node matching '$target' receives ACTION_FOCUS",
                    riskLevel = RiskLevel.SAFE,
                    channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                    spokenResponseDraft = "Focusing $target."
                )
            }
        }
        if (lower.startsWith("select ") && lower != "select photos") {
            val target = text.replaceFirst(Regex("(?i)^select\\s+"), "").trim()
            if (target.isNotBlank()) {
                return PlannedToolCall(
                    toolName = "select_node",
                    arguments = mapOf("target" to target),
                    reasoning = "Matched Accessibility semantic select command for '$target'.",
                    expectedOutcomeDescription = "UI node matching '$target' receives ACTION_SELECT",
                    riskLevel = RiskLevel.SAFE,
                    channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                    spokenResponseDraft = "Selecting $target."
                )
            }
        }

        // 15. Accessibility Text Input
        val typeMatch = Regex("""(?i)^(?:type|enter\s+text)\s+["']?(.+?)["']?(?:\s+into\s+(.+))?$""").find(text)
        if (typeMatch != null) {
            val inputContent = typeMatch.groupValues[1].trim()
            val targetField = typeMatch.groupValues[2].trim()
            val isSensitive = isConsequentialAccessibilityTarget(targetField)
            return PlannedToolCall(
                toolName = "input_text",
                arguments = mapOf("text" to inputContent, "target" to targetField),
                reasoning = "Matched Accessibility text entry command.",
                expectedOutcomeDescription = "Text entered into editable field and verified in post-action tree",
                riskLevel = if (isSensitive) RiskLevel.SENSITIVE_CONFIRM else RiskLevel.SAFE,
                channel = ExecutionChannel.ACCESSIBILITY_SERVICE,
                spokenResponseDraft = "Entering text."
            )
        }

        // 16. Web Search
        if (lower.startsWith("search web for ") || lower.startsWith("google ") || lower.startsWith("open url ")) {
            val query = text.replaceFirst(Regex("(?i)^(search web for|google|open url)\\s+"), "").trim()
            if (query.isNotBlank()) {
                return PlannedToolCall(
                    toolName = "web_search",
                    arguments = mapOf("query" to query),
                    reasoning = "Matched browser search/URL command.",
                    expectedOutcomeDescription = "Browser opens $query",
                    riskLevel = RiskLevel.SAFE,
                    channel = ExecutionChannel.ANDROID_API_INTENT,
                    spokenResponseDraft = "Opening $query in your browser."
                )
            }
        }

        // 17. Launch Installed App
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appName = text.replaceFirst(Regex("(?i)^(open|launch)\\s+"), "").trim()
            if (appName.isNotBlank() && !appName.contains(" ")) {
                return PlannedToolCall(
                    toolName = "launch_app",
                    arguments = mapOf("app" to appName),
                    reasoning = "Matched direct app launch command for '$appName'.",
                    expectedOutcomeDescription = "Installed app '$appName' launches",
                    riskLevel = RiskLevel.SAFE,
                    channel = ExecutionChannel.ANDROID_API_INTENT,
                    spokenResponseDraft = "Opening $appName."
                )
            } else if (appName.isNotBlank() && appName.split(" ").size <= 3) {
                return PlannedToolCall(
                    toolName = "launch_app",
                    arguments = mapOf("app" to appName),
                    reasoning = "Matched multi-word app launch command for '$appName'.",
                    expectedOutcomeDescription = "Installed app '$appName' launches",
                    riskLevel = RiskLevel.SAFE,
                    channel = ExecutionChannel.ANDROID_API_INTENT,
                    spokenResponseDraft = "Opening $appName."
                )
            }
        }

        return null
    }

    private fun isConsequentialAccessibilityTarget(targetLabel: String): Boolean {
        val lower = targetLabel.lowercase()
        return lower.contains("delete") ||
            lower.contains("remove") ||
            lower.contains("send") ||
            lower.contains("pay") ||
            lower.contains("purchase") ||
            lower.contains("confirm") ||
            lower.contains("submit") ||
            lower.contains("transfer") ||
            lower.contains("uninstall") ||
            lower.contains("reset")
    }
}
