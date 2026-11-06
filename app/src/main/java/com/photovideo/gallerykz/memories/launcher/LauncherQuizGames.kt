package com.photovideo.gallerykz.memories.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig

/** One built-in "Quiz game" ad: shown with an "Ad" badge, a tap opens [url] in the browser. */
class QuizGame(val title: String, val url: String, val iconRes: Int)

/**
 * The 40 bundled game ads (icons res/drawable-nodpi/ic_qz_icon_1..40; the list order is the display order) and the Remote Config
 * (`LauncherApp_Quiz_Icon_Show`, `LauncherApp_Quiz_Icon_Count`, `LauncherApp_Game_Folder_Show`) that places them: one game icon after each
 * of the first N apps of the drawer, and / or the two Home folders (games 1-20 and 21-40).
 */
object LauncherQuizGames {

    const val MAX_GAMES = 40
    const val GAMES_PER_FOLDER = 20
    private const val KEY_PREFIX = "quiz_game_"

    /** How many game icons the drawer shows: 0 when the switch is off, else 0..[MAX_GAMES]. */
    val iconCount: Int
        get() {
            val l = AdRemoteConfig.config.launcher
            return if (!l.quizIconShow) 0 else l.quizIconCount.coerceIn(0, MAX_GAMES)
        }

    /** True when the two Home folders hold the games instead of the Google / Tools folders. */
    val foldersShow: Boolean
        get() = AdRemoteConfig.config.launcher.gameFolderShow

    val games: List<QuizGame> = listOf(
        QuizGame("Subway Surfers", "https://gamqora.com/topgames/Subway-Surfers/index.html", R.drawable.ic_qz_icon_1),
        QuizGame("Roblox", "https://gamqora.com/topgames/Roblox/index.html", R.drawable.ic_qz_icon_2),
        QuizGame("Candy Crush Saga", "https://gamqora.com/topgames/Candy-Crush-Saga/index.html", R.drawable.ic_qz_icon_3),
        QuizGame("Ludo King®", "https://gamqora.com/topgames/Ludo-King/index.html", R.drawable.ic_qz_icon_4),
        QuizGame("Temple Run 2", "https://gamqora.com/topgames/Temple-Run-2-Endless-Escape/index.html", R.drawable.ic_qz_icon_5),
        QuizGame("Chess - Play and Learn Online", "https://gamqora.com/topgames/Chess-Play-and-Learn-Online/index.html", R.drawable.ic_qz_icon_6),
        QuizGame("Tile Club", "https://gamqora.com/topgames/Tile-Club-Match-Puzzle-Games/index.html", R.drawable.ic_qz_icon_7),
        QuizGame("Archery Battle 3D", "https://gamqora.com/topgames/Archery-Battle-3D/index.html", R.drawable.ic_qz_icon_8),
        QuizGame("PUBG MOBILE", "https://gamqora.com/topgames/PUBG-MOBILE/index.html", R.drawable.ic_qz_icon_9),
        QuizGame("Block Blast!", "http://gamqora.com/topgames/Block-Blast/index.html", R.drawable.ic_qz_icon_10),
        QuizGame("Water Sort Puzzle", "https://gamqora.com/topgames/Water-Sort-Puzzle-Sort-Color/index.html", R.drawable.ic_qz_icon_11),
        QuizGame("Hill Climb Racing", "https://gamqora.com/topgames/Hill-Climb-Racing/index.html", R.drawable.ic_qz_icon_12),
        QuizGame("Worms Zone .io", "https://gamqora.com/topgames/Worms-Zone-io-Hungry-Snake/index.html", R.drawable.ic_qz_icon_13),
        QuizGame("Angry Birds 2", "https://gamqora.com/topgames/Angry-Birds-2/index.html", R.drawable.ic_qz_icon_14),
        QuizGame("Harry Potter Hogwarts Mystery", "https://gamqora.com/topgames/Harry-Potter-Hogwarts-Mystery/index.html", R.drawable.ic_qz_icon_15),
        QuizGame("My Talking Angela", "https://gamqora.com/topgames/My-Talking-Angela/index.html", R.drawable.ic_qz_icon_16),
        QuizGame("2 Player Games", "https://gamqora.com/topgames/2-Player-games-the-Challenge/index.html", R.drawable.ic_qz_icon_17),
        QuizGame("Word Search Explorer", "https://gamqora.com/topgames/Word-Search-Explorer/index.html", R.drawable.ic_qz_icon_18),
        QuizGame("Solitaire", "https://gamqora.com/topgames/Solitaire---Classic-Card-Game/index.html", R.drawable.ic_qz_icon_19),
        QuizGame("Water Sort Puz", "https://gamqora.com/topgames/Water-Sort-Puz---Color-Game/index.html", R.drawable.ic_qz_icon_20),
        QuizGame("Empires & Puzzles", "https://gamqora.com/topgames/Empires-Puzzles-Match-3-RPG/index.html", R.drawable.ic_qz_icon_21),
        QuizGame("Snake.io", "https://gamqora.com/topgames/Snakeio-Fun-Snake-io-Games/index.html", R.drawable.ic_qz_icon_22),
        QuizGame("Race Master 3D", "https://gamqora.com/topgames/Race-Master-3D-Car-Racing/index.html", R.drawable.ic_qz_icon_23),
        QuizGame("Last War Survival Game", "https://gamqora.com/topgames/Last-WarSurvival-Game/index.html", R.drawable.ic_qz_icon_24),
        QuizGame("Fishdom", "https://gamqora.com/topgames/Fishdom/index.html", R.drawable.ic_qz_icon_25),
        QuizGame("Amaze GO!", "https://gamqora.com/topgames/Amaze-GO/index.html", R.drawable.ic_qz_icon_26),
        QuizGame("Car Parking Multiplayer", "https://gamqora.com/topgames/Car-Parking-Multiplayer/index.html", R.drawable.ic_qz_icon_27),
        QuizGame("8 Ball Pool", "https://gamqora.com/topgames/8-Ball-Pool/index.html", R.drawable.ic_qz_icon_28),
        QuizGame("UNO!", "https://gamqora.com/topgames/UNOTM/index.html", R.drawable.ic_qz_icon_29),
        QuizGame("Sudoku.com", "https://gamqora.com/topgames/Sudokucom-Classic-Sudoku/index.html", R.drawable.ic_qz_icon_30),
        QuizGame("Sniper Battle", "https://gamqora.com/topgames/Sniper-Battle-3D-War-Shooter/index.html", R.drawable.ic_qz_icon_31),
        QuizGame("Darts of Fury", "https://gamqora.com/topgames/Darts-of-Fury/index.html", R.drawable.ic_qz_icon_32),
        QuizGame("Satisfying Toys", "https://gamqora.com/topgames/Satisfying-Toys-Calm-Games/index.html", R.drawable.ic_qz_icon_33),
        QuizGame("My Talking Tom", "https://gamqora.com/topgames/My-Talking-Tom/index.html", R.drawable.ic_qz_icon_34),
        QuizGame("Marvel Contest of Champions", "https://gamqora.com/topgames/Marvel-Contest-of-Champions/index.html", R.drawable.ic_qz_icon_35),
        QuizGame("Stickman Party", "https://gamqora.com/topgames/Stickman-Party-234-MiniGames/index.html", R.drawable.ic_qz_icon_36),
        QuizGame("Snake Clash!", "https://gamqora.com/topgames/Snake-Clash/index.html", R.drawable.ic_qz_icon_37),
        QuizGame("Masha and the Bear Pizza Maker", "https://gamqora.com/topgames/Masha-and-the-Bear-Pizza-Maker/index.html", R.drawable.ic_qz_icon_38),
        QuizGame("Monster Trucks Game for Kids 3", "https://gamqora.com/topgames/Monster-Trucks-Game-for-Kids-3/index.html", R.drawable.ic_qz_icon_39),
        QuizGame("Match Factory!", "http://gamqora.com/topgames/Match-Factory/index.html", R.drawable.ic_qz_icon_40)

    )

    @Volatile
    private var cache: List<LauncherApp>? = null

    /** The games as list rows (label = title, [LauncherApp.gameUrl] = link). Built once; icons that fail to load are skipped. */
    fun apps(context: Context): List<LauncherApp> {
        cache?.let { return it }
        val appContext = context.applicationContext
        val built = games.mapIndexedNotNull { index, game ->
            val icon = ContextCompat.getDrawable(appContext, game.iconRes) ?: return@mapIndexedNotNull null
            LauncherApp(game.title, "$KEY_PREFIX${index + 1}", "game", 0L, false, icon, gameUrl = game.url)
        }
        cache = built
        return built
    }

    /** Opens a game link in the browser. Never throws: no browser or a bad link just does nothing. */
    fun openLink(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No app can open the link.
        }
    }
}
