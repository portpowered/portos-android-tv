package com.portoperatingsystem.tv.surface.render

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/** The A2UI basic-catalog icon names, each mapped to a Material icon. An unknown name renders no glyph. */
internal object IconNames {
    fun vector(name: String?): ImageVector? = icons[name]

    /** Every name that renders a glyph, in the registration's Icon schema. */
    val names: List<String> get() = icons.keys.sorted()

    private val icons: Map<String, ImageVector> = mapOf(
        "accountCircle" to Icons.Filled.AccountCircle,
        "add" to Icons.Filled.Add,
        "arrowBack" to Icons.AutoMirrored.Filled.ArrowBack,
        "arrowForward" to Icons.AutoMirrored.Filled.ArrowForward,
        "attachFile" to Icons.Filled.AttachFile,
        "calendarToday" to Icons.Filled.CalendarToday,
        "call" to Icons.Filled.Call,
        "camera" to Icons.Filled.CameraAlt,
        "check" to Icons.Filled.Check,
        "close" to Icons.Filled.Close,
        "delete" to Icons.Filled.Delete,
        "download" to Icons.Filled.Download,
        "edit" to Icons.Filled.Edit,
        "event" to Icons.Filled.Event,
        "error" to Icons.Filled.Error,
        "fastForward" to Icons.Filled.FastForward,
        "favorite" to Icons.Filled.Favorite,
        "favoriteOff" to Icons.Filled.FavoriteBorder,
        "folder" to Icons.Filled.Folder,
        "help" to Icons.AutoMirrored.Filled.Help,
        "home" to Icons.Filled.Home,
        "info" to Icons.Filled.Info,
        "locationOn" to Icons.Filled.LocationOn,
        "lock" to Icons.Filled.Lock,
        "lockOpen" to Icons.Filled.LockOpen,
        "mail" to Icons.Filled.Mail,
        "menu" to Icons.Filled.Menu,
        "moreVert" to Icons.Filled.MoreVert,
        "moreHoriz" to Icons.Filled.MoreHoriz,
        "notificationsOff" to Icons.Filled.NotificationsOff,
        "notifications" to Icons.Filled.Notifications,
        "pause" to Icons.Filled.Pause,
        "payment" to Icons.Filled.Payment,
        "person" to Icons.Filled.Person,
        "phone" to Icons.Filled.Phone,
        "photo" to Icons.Filled.Photo,
        "play" to Icons.Filled.PlayArrow,
        "print" to Icons.Filled.Print,
        "refresh" to Icons.Filled.Refresh,
        "rewind" to Icons.Filled.FastRewind,
        "search" to Icons.Filled.Search,
        "send" to Icons.AutoMirrored.Filled.Send,
        "settings" to Icons.Filled.Settings,
        "share" to Icons.Filled.Share,
        "shoppingCart" to Icons.Filled.ShoppingCart,
        "skipNext" to Icons.Filled.SkipNext,
        "skipPrevious" to Icons.Filled.SkipPrevious,
        "star" to Icons.Filled.Star,
        "starHalf" to Icons.AutoMirrored.Filled.StarHalf,
        "starOff" to Icons.Filled.StarBorder,
        "stop" to Icons.Filled.Stop,
        "upload" to Icons.Filled.Upload,
        "visibility" to Icons.Filled.Visibility,
        "visibilityOff" to Icons.Filled.VisibilityOff,
        "volumeDown" to Icons.AutoMirrored.Filled.VolumeDown,
        "volumeMute" to Icons.AutoMirrored.Filled.VolumeMute,
        "volumeOff" to Icons.AutoMirrored.Filled.VolumeOff,
        "volumeUp" to Icons.AutoMirrored.Filled.VolumeUp,
        "warning" to Icons.Filled.Warning
    )
}
