package com.example.solanakotlincomposescaffold.di

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.solanakotlincomposescaffold.managers.BluetoothManagerHelper
import com.example.solanakotlincomposescaffold.managers.UsbManagerHelper
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val solanaUri = Uri.parse("https://solana.com")
val iconUri = Uri.parse("favicon.ico")
val identityName = "Solana"

@InstallIn(ViewModelComponent::class)
@Module
class SolanaKotlinComposeScaffoldModule {

    @Provides
    fun providesSharedPrefs(@ApplicationContext ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences("scaffold_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    fun providesMobileWalletAdapter(): MobileWalletAdapter {
        return MobileWalletAdapter(connectionIdentity = ConnectionIdentity(
            identityUri = solanaUri,
            iconUri = iconUri,
            identityName = identityName
        ))
    }
}

@InstallIn(SingletonComponent::class)
@Module
class ManagerModule {
    
    @Provides
    @Singleton
    fun providesBluetoothManager(@ApplicationContext context: Context): BluetoothManagerHelper {
        return BluetoothManagerHelper(context)
    }
    
    @Provides
    @Singleton
    fun providesUsbManager(@ApplicationContext context: Context): UsbManagerHelper {
        return UsbManagerHelper(context)
    }
}