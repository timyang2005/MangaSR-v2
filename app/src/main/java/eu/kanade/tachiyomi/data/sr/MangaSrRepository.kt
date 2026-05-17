package eu.kanade.tachiyomi.data.sr

import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaSrRepository(
    private val mangaRepository: MangaRepository = Injekt.get(),
) {
    suspend fun setSrEnabled(mangaId: Long, enabled: Boolean) {
        mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                srEnabled = enabled,
            ),
        )
    }

    suspend fun setSrModel(mangaId: Long, model: String) {
        mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                srModel = model,
            ),
        )
    }

    suspend fun setSrNoiseLevel(mangaId: Long, noiseLevel: Int) {
        mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                srNoiseLevel = noiseLevel,
            ),
        )
    }
}
