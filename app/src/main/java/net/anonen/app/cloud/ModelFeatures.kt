package net.anonen.app.cloud

fun wantsFillerRemoval(
    settingOn: Boolean,
    model: CloudModelInfo?,
): Boolean = settingOn && model?.supportsFillerRemoval == true

data class WordsGroup(
    val languageChoice: Boolean,
    val fillerSwitch: Boolean,
) {
    val isEmpty: Boolean get() = !languageChoice && !fillerSwitch
}

fun wordsGroupFor(cloudInfo: CloudModelInfo?): WordsGroup =
    WordsGroup(
        languageChoice = cloudInfo == null || cloudInfo.supportedLanguages.isNotEmpty(),
        fillerSwitch = cloudInfo?.supportsFillerRemoval == true,
    )
