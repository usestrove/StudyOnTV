package com.studyontv.parent

/**
 * 常见国内电视 / 盒子 / 投影仪开启无线 ADB（TCP 5555）的三步速查指南。
 */
object BrandGuides {
    data class Guide(
        val id: String,
        val brand: String,
        val steps: List<String>,
        val tip: String
    )

    val ALL: List<Guide> = listOf(
        Guide(
            id = "xiaomi",
            brand = "小米 / 红米",
            steps = listOf(
                "设置 → 关于 → 连按 7 次型号/版本号开开发者模式",
                "设置 → 账号与安全 → 开启「安装未知来源」与「ADB 调试」"
            ),
            tip = ""
        ),
        Guide(
            id = "tcl",
            brand = "TCL / 雷鸟",
            steps = listOf(
                "设置 → 系统 → 关于 → 连按版本号开开发者选项",
                "开发者选项 → 开启「USB / 网络 ADB 调试」"
            ),
            tip = ""
        ),
        Guide(
            id = "hisense",
            brand = "海信 / Vidda",
            steps = listOf(
                "设置 → 关于本机 → 连按确认键开开发者选项",
                "开发者选项 / 安全 → 开启「ADB 调试」"
            ),
            tip = ""
        ),
        Guide(
            id = "skyworth",
            brand = "创维 / 酷开",
            steps = listOf(
                "设置 → 系统信息 → 连按 7 次确认键开开发者选项",
                "开启「ADB 调试」"
            ),
            tip = ""
        ),
        Guide(
            id = "sony",
            brand = "索尼",
            steps = listOf(
                "设置 → 系统 → 关于 → 连按 7 次系统版本",
                "开发者选项 → 开启「USB 调试」"
            ),
            tip = ""
        ),
        Guide(
            id = "box",
            brand = "盒子 / 投影",
            steps = listOf(
                "设置 → 关于 → 连按版本号开开发者选项",
                "开发者选项 → 开启「ADB / 网络调试」"
            ),
            tip = ""
        ),
        Guide(
            id = "phone",
            brand = "手机 / 平板",
            steps = listOf(
                "开发者选项 → 开启「无线调试」查看 IP 与端口",
                "下方按 IP:端口 填入（已开 5555 端口可直接填 IP）"
            ),
            tip = ""
        )
    )
}
