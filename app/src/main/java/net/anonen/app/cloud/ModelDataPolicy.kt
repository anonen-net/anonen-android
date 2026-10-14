package net.anonen.app.cloud

data class ModelDataPolicy(
    val training: String,
    val retention: String,
    val fingerprint: String,
) {
    val isUnknown: Boolean get() = training == UNKNOWN && retention == UNKNOWN

    companion object {
        const val UNKNOWN = "確かめていません"

        val PREVIEW_NOTICE: List<String> =
            listOf(
                "あのねんで試したところ、とても精度が良かったので試験的に追加しています。",
                "提供元ではまだ試験版で、個人の情報には使わないよう勧められています。",
                "正式版になったら規約を確かめ直し、正式に追加する予定です。",
            )
    }
}

fun CloudModelInfo.dataPolicy(): ModelDataPolicy =
    ModelDataPolicy(
        training =
            when (trainingUse) {
                "no" -> "使われません"

                "opt_out_applied" -> "使われません（あのねんが、使わせない設定にしています）"
                "yes" -> "使われます"
                else -> ModelDataPolicy.UNKNOWN
            },
        retention =
            when (retentionKind) {
                "none" -> "残りません"
                "days" -> retentionDays?.let { "$it 日間残ってから消えます" } ?: ModelDataPolicy.UNKNOWN

                "unspecified" -> "いつ消えるかの決まりがありません"
                else -> ModelDataPolicy.UNKNOWN
            },
        fingerprint = "$id@$trainingUse/$retentionKind/${retentionDays ?: "-"}" + if (isPreview) "/preview" else "",
    )
