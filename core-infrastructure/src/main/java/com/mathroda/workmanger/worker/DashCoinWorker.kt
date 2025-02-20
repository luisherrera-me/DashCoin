package com.mathroda.workmanger.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mathroda.core.state.UserState
import com.mathroda.core.util.Constants
import com.mathroda.core.util.Constants.BITCOIN_ID
import com.mathroda.core.util.Resource
import com.mathroda.datasource.core.DashCoinRepository
import com.mathroda.datasource.usecases.DashCoinUseCases
import com.mathroda.notifications.coins.CoinsNotification
import com.mathroda.notifications.coins.showNegative
import com.mathroda.notifications.coins.showPositive
import com.mathroda.workmanger.util.is5PercentDown
import com.mathroda.workmanger.util.is5PercentUp
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class DashCoinWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val dashCoinRepository: DashCoinRepository,
    private val dashCoinUseCases: DashCoinUseCases,
    private val notification: CoinsNotification
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

       return@withContext try {
            dashCoinUseCases.userStateProvider(
               function = {}
            ).collect { state ->
                when(state) {
                    is UserState.UnauthedUser -> regularUserNotification(state)
                    is UserState.AuthedUser -> regularUserNotification(state)
                    is UserState.PremiumUser -> premiumUserNotification(state)
                }
            }
            Result.success()
        } catch (exception: Exception) {
            Result.failure()
        }
    }

    private suspend fun regularUserNotification(state: UserState) {
        dashCoinRepository.getCoinByIdRemote(BITCOIN_ID).collect { result ->
            if (result is Resource.Success) {
                result.data?.let { coin ->
                    if (coin.priceChange1d > 0) {
                        notification.showPositive(state)
                    }

                    if (coin.priceChange1d < 0) {
                        notification.showNegative(state)
                    }

                    return@collect
                }
            }
        }
    }

    private suspend fun premiumUserNotification(state: UserState) {
        dashCoinUseCases.getAllFavoriteCoins().collect { result ->
            if (result is Resource.Success) {
                if (result.data.isNullOrEmpty()) {
                    return@collect
                }

                result.data?.let { coins ->
                    coins.forEach {
                        if (it.priceChanged1d.is5PercentUp()) {
                            notification.show(
                                title = it.name ,
                                description = Constants.DESCRIPTION_MARKET_CHANGE_POSITIVE,
                                id = it.rank,
                                state = state
                            )
                        }

                        if (it.priceChanged1d.is5PercentDown()) {
                            notification.show(
                                title = it.name ,
                                description = Constants.DESCRIPTION_MARKET_CHANGE_NEGATIVE,
                                id = it.rank,
                                state = state
                            )
                        }
                    }
                }
            }
        }
    }
}