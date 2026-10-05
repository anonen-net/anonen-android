package net.anonen.app.cloud

data class ModelDataPolicy(
    val training: String,
    val retention: String,
    val fingerprint: String,
) {
    val isUnknown: Boolean get() = training == UNKNOWN && retention == UNKNOWN

    companion object {
        const val UNKNOWN = "確かめていません"
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
        fingerprint = "$id@$trainingUse/$retentionKind/${retentionDays ?: "-"}",
    )
