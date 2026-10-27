package com.ytstyle.bili.data.net

import javax.inject.Qualifier

/**
 * Distinguishes the two Retrofit instances. Without this, Dagger reports a
 * duplicate binding because both provide the same type.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiRetrofit
