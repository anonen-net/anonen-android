package net.anonen.app.inject

internal enum class InjectionPlan {
    INPUT_CONNECTION_COMMIT,
    STANDARD_SET_TEXT,
    ACCESSIBILITY_PASTE,
    CLIPBOARD_FALLBACK,
}

internal object TextInjectionPolicy {
    fun planFor(
        className: CharSequence?,
        packageName: CharSequence?,
        hasSetText: Boolean,
        hasPaste: Boolean,
    ): InjectionPlan =
        when {
            requiresInputConnectionCommit(packageName) -> InjectionPlan.INPUT_CONNECTION_COMMIT
            isStandardEditText(className) && hasSetText -> InjectionPlan.STANDARD_SET_TEXT
            hasPaste -> InjectionPlan.ACCESSIBILITY_PASTE
            else -> InjectionPlan.CLIPBOARD_FALLBACK
        }

    fun isStandardEditText(className: CharSequence?): Boolean = className?.toString() in STANDARD_EDIT_TEXT_CLASSES

    fun requiresInputConnectionCommit(packageName: CharSequence?): Boolean =
        packageName?.toString() in INPUT_CONNECTION_COMMIT_PACKAGES

    private val STANDARD_EDIT_TEXT_CLASSES =
        setOf(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.MultiAutoCompleteTextView",
            "androidx.appcompat.widget.AppCompatEditText",
            "androidx.appcompat.widget.AppCompatAutoCompleteTextView",
            "com.google.android.material.textfield.TextInputEditText",
        )

    private val INPUT_CONNECTION_COMMIT_PACKAGES =
        setOf(
            "md.obsidian",
        )
}
