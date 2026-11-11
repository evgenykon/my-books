package io.github.evgenykon.mybooks

import android.app.Application

class MyBooksApplication : Application() {
    val library by lazy { io.github.evgenykon.mybooks.data.LibraryRepository(this) }
}
