package mihon.core.superresolution

import android.content.Context
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File

class ModelManager private constructor(
    private val context: Context,
) {
    companion object {
        @Volatile
        private var instance: ModelManager? = null

        fun getInstance(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val modelsDir by lazy {
        File(context.filesDir, "models").also { it.mkdirs() }
    }

    fun getModelDir(model: SRModel): File {
        return File(modelsDir, model.modelDirName)
    }

    fun isModelReady(model: SRModel): Boolean {
        if (!model.isBuiltIn) return false
        val dir = getModelDir(model)
        val paramFile = File(dir, "${model.modelDirName}.param")
        val binFile = File(dir, "${model.modelDirName}.bin")
        return paramFile.exists() && binFile.exists()
    }

    fun ensureModelsExtracted() {
        // modelsDir 通过 lazy 延迟初始化，目录在首次访问时由 also { it.mkdirs() } 创建
        SRModel.entries.filter { it.isBuiltIn }.forEach { model ->
            val modelDir = File(modelsDir, model.modelDirName)
            if (!modelDir.exists() || !isModelReady(model)) {
                modelDir.mkdirs()
                copyModelAssets(model)
            }
        }
    }

    private fun copyModelAssets(model: SRModel) {
        val modelDir = getModelDir(model)
        try {
            context.assets.list("models/${model.modelDirName}")?.forEach { filename ->
                context.assets.open("models/${model.modelDirName}/$filename").use { input ->
                    File(modelDir, filename).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            logcat(LogPriority.INFO) { "Model extracted: ${model.modelDirName}" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to extract model: ${model.modelDirName}\n${e.asLog()}" }
        }
    }
}
