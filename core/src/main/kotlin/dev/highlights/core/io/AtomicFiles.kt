package dev.highlights.core.io

import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Remplacement d'un fichier par sa copie temporaire, sans instant où il n'existe plus. */
object AtomicFiles {
    /**
     * Met [tmp] à la place de [target]. Sous Windows, `Files.move(REPLACE_EXISTING)` sans `ATOMIC_MOVE` efface d'abord
     * la cible puis renomme : un lecteur qui passe entre les deux ne trouve plus le fichier (la bibliothèque cachait
     * alors une analyse dont la session se réécrivait : test du dossier surveillé en échec sur la CI de la 1.8.0).
     * `ATOMIC_MOVE` remplace d'un coup (MoveFileEx) ; repli sur le déplacement simple si le volume ne le permet pas.
     */
    fun replace(tmp: Path, target: Path) {
        var attempt = 0
        while (true) {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: AccessDeniedException) {
                // Windows refuse de remplacer un fichier qu'un autre fil tient ouvert à cet instant (lecture de
                // l'index, test d'existence) : il le relâche en quelques millisecondes (AtomicFilesTest).
                if (++attempt >= RETRIES) throw e
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private const val RETRIES = 100
    private const val RETRY_DELAY_MS = 5L
}
