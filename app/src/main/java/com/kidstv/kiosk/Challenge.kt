package com.kidstv.kiosk

import kotlin.random.Random

/**
 * 家长门禁的题目。
 *
 * 三档：
 *  - PIN     ：沿用固定数字密码，不出题
 *  - ADD_SUB ：两位数加减，**强制进位/借位**（先构造条件再拼数，不是生成后筛选）
 *  - MUL_DIV ：两位数×一位数 / 整除除法（先定商和除数再相乘，保证整除）
 *
 * 题目落盘在 Config 里，10 分钟内复用同一道 —— 被系统杀掉重建后不能换题，
 * 否则家长算到一半题变了。
 */
object Challenge {

    private const val TTL_MS = 10 * 60_000L

    enum class Mode(val key: String, val label: String) {
        PIN("pin", "纯数字密码"),
        ADD_SUB("addsub", "两位数加减"),
        MUL_DIV("muldiv", "两位数乘除");

        companion object {
            fun of(key: String?): Mode = values().firstOrNull { it.key == key } ?: PIN
        }
    }

    data class Question(val prompt: String, val answer: String)

    fun currentMode(): Mode = Mode.of(Config.gateMode)

    /** 取当前题目：还新鲜就用盘上的，否则重新出 */
    fun current(mode: Mode = currentMode()): Question {
        if (mode == Mode.PIN) {
            return Question("请输入家长密码", Config.pin)
        }
        val saved = Config.chPrompt
        val answer = Config.chAnswer
        if (saved.isNotEmpty() && answer.isNotEmpty() &&
            System.currentTimeMillis() - Config.chBornAt < TTL_MS
        ) {
            return Question(saved, answer)
        }
        return refresh(mode)
    }

    fun refresh(mode: Mode = currentMode()): Question {
        if (mode == Mode.PIN) return Question("请输入家长密码", Config.pin)
        val q = build(mode)
        Config.chPrompt = q.prompt
        Config.chAnswer = q.answer
        Config.chBornAt = System.currentTimeMillis()
        return q
    }

    private fun build(mode: Mode): Question {
        return when (mode) {
            Mode.MUL_DIV -> if (Random.nextBoolean()) mul() else div()
            else -> if (Random.nextBoolean()) add() else sub()
        }
    }

    /** 两位数加法，个位相加必进位，和不超过 99 */
    private fun add(): Question {
        while (true) {
            val a1 = Random.nextInt(3, 9)          // 3..8
            val b1 = Random.nextInt(10 - a1, 10)   // 保证 a1+b1 >= 10
            val a10 = Random.nextInt(2, 8)         // 20..79
            val b10 = Random.nextInt(1, 7)         // 10..69
            val a = a10 * 10 + a1
            val b = b10 * 10 + b1
            if (a + b <= 99) {
                return Question("$a + $b = ?", (a + b).toString())
            }
        }
    }

    /** 两位数减法，个位必借位，差仍是两位数 */
    private fun sub(): Question {
        while (true) {
            val b10 = Random.nextInt(1, 6)         // 10..59
            val a10 = Random.nextInt(b10 + 1, 10)  // 保证差 >= 10
            val b1 = Random.nextInt(2, 10)         // 2..9
            val a1 = Random.nextInt(0, b1)         // 个位不够减 -> 借位
            val a = a10 * 10 + a1
            val b = b10 * 10 + b1
            if (a - b >= 10) {
                return Question("$a - $b = ?", (a - b).toString())
            }
        }
    }

    /** 两位数 × 一位数 */
    private fun mul(): Question {
        val a = Random.nextInt(12, 30)
        val b = Random.nextInt(3, 10)
        return Question("$a × $b = ?", (a * b).toString())
    }

    /** 整除除法：先定商和除数，再乘出被除数 */
    private fun div(): Question {
        val b = Random.nextInt(3, 10)
        val q = Random.nextInt(4, 30)
        val a = b * q
        return Question("$a ÷ $b = ?", q.toString())
    }
}
