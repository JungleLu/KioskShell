package com.kidstv.kiosk

import kotlin.random.Random

/**
 * 休息页上的题目。
 *
 * 只有数学，而且是**程序生成**的，不存题库：
 * 无限量、永不重复、零版权、答案绝对正确 —— 这四点里任何一条不成立，
 * 家长就会截图说「这软件有问题」。
 *
 * 全部题型都保证**答案是整数**。这不是偷懒：分数、小数、几何的答案一旦出现
 * 「4 还是 4.0」这种写法差异，孩子答对了也会被判错，而那是最伤信任的一种错。
 *
 * 题目不落盘。休息页每次重建都出新题，孩子没法靠反复进出记住答案 ——
 * 限制尝试次数（[MAX_TRIES]）才是防蒙的正道，题目难度不是。
 */
object Quiz {

    /** 一次休息最多答几次。4 选 1 蒙对 25%，给 2 次是 44%，再往上就是纯蒙 */
    const val MAX_TRIES = 2

    /**
     * 一道题。
     *
     * 选项里**带着**正确答案（顺序已打乱），所以判分是字符串相等，
     * 不需要额外传一个 answerIndex —— 少一个可能写错的下标。
     */
    data class Item(val prompt: String, val answer: String, val options: List<String>)

    fun gradeLabel(grade: Int): String = when (grade.coerceIn(1, 6)) {
        1 -> "一年级"
        2 -> "二年级"
        3 -> "三年级"
        4 -> "四年级"
        5 -> "五年级"
        else -> "六年级"
    }

    /** 今天还能答几次 */
    fun remainingToday(): Int = (Config.quizDailyMax - Config.quizUsedToday).coerceAtLeast(0)

    /**
     * 这一页该不该出题。
     *
     * 两个条件：家长开着（`quizDailyMax > 0`）、今天还有次数。
     * **不看「今天额度用完」那个页** —— 那是硬上限，不该被做题突破，
     * 所以调用方只在 MODE_REST 里问这个方法。
     */
    fun available(): Boolean = Config.quizDailyMax > 0 && remainingToday() > 0

    /** 出一道新题 */
    fun next(): Item {
        val grade = Config.quizGrade
        val (prompt, answer) = build(grade)
        return Item(prompt, answer.toString(), options(answer))
    }

    // ---------------- 出题 ----------------

    /**
     * 按年级出题。全部「先构造条件再拼数」，不是生成后筛选 ——
     * 后者在低年级会反复重试（要求和不进位、差为正…），还可能死循环。
     */
    private fun build(grade: Int): Pair<String, Int> = when (grade) {
        1 -> addSub(20)
        2 -> if (Random.nextBoolean()) timesTable() else addSub(100)
        3 -> if (Random.nextBoolean()) twoByOne() else exactDiv(2, 9)
        4 -> if (Random.nextBoolean()) threeByTwo() else mixed()
        5 -> when (Random.nextInt(3)) {
            0 -> twoByTwo()
            1 -> exactDiv(2, 9)
            else -> equation()
        }
        else -> when (Random.nextInt(3)) {
            0 -> percent()
            1 -> ratio()
            else -> equation()
        }
    }

    /** 加减法。加法留出进位空间，减法保证结果至少是 2，别出 5-4 这种 */
    private fun addSub(max: Int): Pair<String, Int> {
        return if (Random.nextBoolean()) {
            val a = Random.nextInt(max / 3, max)      // a < max，下面 b 的范围才不会空
            val b = Random.nextInt(1, max - a + 1)
            "$a + $b = ?" to (a + b)
        } else {
            val a = Random.nextInt(3, max + 1)
            val b = Random.nextInt(1, a - 1)          // a >= 3，所以上界至少是 2
            "$a - $b = ?" to (a - b)
        }
    }

    /** 表内乘法 */
    private fun timesTable(): Pair<String, Int> {
        val a = Random.nextInt(2, 10)
        val b = Random.nextInt(2, 10)
        return "$a × $b = ?" to (a * b)
    }

    /** 两位数 × 一位数 */
    private fun twoByOne(): Pair<String, Int> {
        val a = Random.nextInt(12, 100)
        val b = Random.nextInt(3, 10)
        return "$a × $b = ?" to (a * b)
    }

    /** 两位数 × 两位数 */
    private fun twoByTwo(): Pair<String, Int> {
        val a = Random.nextInt(11, 100)
        val b = Random.nextInt(11, 100)
        return "$a × $b = ?" to (a * b)
    }

    /** 三位数 × 两位数。乘数压在 30 以内，答案不会大到电视上一行放不下 */
    private fun threeByTwo(): Pair<String, Int> {
        val a = Random.nextInt(101, 1000)
        val b = Random.nextInt(11, 30)
        return "$a × $b = ?" to (a * b)
    }

    /** 整除除法：先定商和除数，再乘出被除数 —— 保证除得尽，不会出现循环小数 */
    private fun exactDiv(divFrom: Int, divTo: Int): Pair<String, Int> {
        val b = Random.nextInt(divFrom, divTo + 1)
        val q = Random.nextInt(2, 13)
        val a = b * q
        return "$a ÷ $b = ?" to q
    }

    /** 带括号的四则混合 */
    private fun mixed(): Pair<String, Int> {
        val a = Random.nextInt(5, 30)
        val b = Random.nextInt(5, 30)
        val c = Random.nextInt(2, 10)
        return if (Random.nextBoolean()) {
            "($a + $b) × $c = ?" to ((a + b) * c)
        } else {
            // a、b 都 >= 5，所以 a*b 至少 25，减掉 c（<=9）不会为负
            "$a × $b - $c = ?" to (a * b - c)
        }
    }

    /** 简易方程。只出「加」和「乘」，减法容易构造出负数 */
    private fun equation(): Pair<String, Int> {
        val x = Random.nextInt(3, 30)
        return if (Random.nextBoolean()) {
            val b = Random.nextInt(5, 60)
            "x + $b = ${x + b}，x = ?" to x
        } else {
            val b = Random.nextInt(2, 10)
            "x × $b = ${x * b}，x = ?" to x
        }
    }

    /** 百分数。基数从能整除的那几个里挑，避免出现 33% 这种除不尽 */
    private fun percent(): Pair<String, Int> {
        val bases = intArrayOf(20, 40, 60, 80, 100, 120, 200)
        val percents = intArrayOf(10, 20, 25, 50, 75, 80)
        val base = bases[Random.nextInt(bases.size)]
        val p = percents[Random.nextInt(percents.size)]
        return "$base 的 $p% 是多少？" to (base * p / 100)
    }

    /** 比例：a : b = a*k : ? */
    private fun ratio(): Pair<String, Int> {
        val a = Random.nextInt(2, 9)
        val b = Random.nextInt(2, 9)
        val k = Random.nextInt(2, 7)
        return "$a : $b = ${a * k} : ?" to (b * k)
    }

    // ---------------- 干扰项 ----------------

    /**
     * 三个干扰项，围绕正确答案按「量级」上下浮动。
     *
     * 为什么要按量级：如果答案 3000，干扰项写成 3001 一眼假；写成 300 又太远。
     * 按量级取 1~3 个数位单位（3000 → 2800/3100/3300），看起来才像真算错。
     *
     * 答案很小时（比如 3）候选会不够，末尾用连续整数兜底，保证一定能凑够 3 个。
     */
    private fun options(answer: Int): List<String> {
        val wrong = LinkedHashSet<Int>()
        val step = when {
            answer >= 2000 -> 100
            answer >= 300 -> 10
            answer >= 30 -> 2
            else -> 1
        }
        var guard = 0
        while (wrong.size < 3 && guard++ < 160) {
            val delta = step * Random.nextInt(1, 4)
            val sign = if (Random.nextBoolean()) 1 else -1
            val v = answer + sign * delta
            if (v > 0 && v != answer) wrong.add(v)
        }
        var filler = answer + 1
        while (wrong.size < 3) {
            if (filler > 0 && filler != answer) wrong.add(filler)
            filler++
        }
        return (wrong + answer).shuffled().map { it.toString() }
    }
}
