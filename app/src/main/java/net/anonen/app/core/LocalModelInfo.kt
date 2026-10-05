package net.anonen.app.core

data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val dirName: String,
    val files: List<ModelFile>,
    val accuracyScore: Float,
    val speedScore: Float,
) {
    data class ModelFile(
        val url: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }

    companion object {
        private const val HF_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/6a65851"

        val SENSEVOICE =
            LocalModelInfo(
                id = "local-sensevoice",
                displayName = "SenseVoice（スマホの中）",
                description = "日本語など 5 つの言葉・239MB",
                dirName = "sensevoice",
                accuracyScore = 0.55f,
                speedScore = 0.90f,
                files =
                    listOf(
                        ModelFile(
                            url = "$HF_BASE/model.int8.onnx",
                            fileName = "model.int8.onnx",
                            sizeBytes = 239_233_841L,
                            sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                        ),
                        ModelFile(
                            url = "$HF_BASE/tokens.txt",
                            fileName = "tokens.txt",
                            sizeBytes = 315_894L,
                            sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                        ),
                    ),
            )

        val ALL = listOf(SENSEVOICE)

        fun fromId(id: String): LocalModelInfo? = ALL.firstOrNull { it.id == id }
    }
}
