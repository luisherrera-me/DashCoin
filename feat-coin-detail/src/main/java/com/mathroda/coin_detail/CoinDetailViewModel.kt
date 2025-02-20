package com.mathroda.coin_detail

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.Entry
import com.mathroda.coin_detail.components.TimeRange
import com.mathroda.coin_detail.state.ChartState
import com.mathroda.coin_detail.state.CoinState
import com.mathroda.common.events.FavoriteCoinEvents
import com.mathroda.common.state.DialogState
import com.mathroda.core.state.IsFavoriteState
import com.mathroda.core.state.UserState
import com.mathroda.core.util.Constants.PARAM_COIN_ID
import com.mathroda.core.util.Resource
import com.mathroda.datasource.core.DashCoinRepository
import com.mathroda.datasource.usecases.DashCoinUseCases
import com.mathroda.domain.model.ChartTimeSpan
import com.mathroda.domain.model.CoinById
import com.mathroda.domain.model.FavoriteCoin
import com.mathroda.domain.model.toFavoriteCoin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoinDetailViewModel @Inject constructor(
    private val dashCoinRepository: DashCoinRepository,
    private val dashCoinUseCases: DashCoinUseCases,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _coinState = MutableStateFlow(CoinState())
    val coinState = _coinState.asStateFlow()

    private val _chartState = mutableStateOf(ChartState())
    val chartState: State<ChartState> = _chartState

    private val _favoriteMsg = mutableStateOf(IsFavoriteState.Messages())
    val favoriteMsg: State<IsFavoriteState.Messages> = _favoriteMsg

    private val _sideEffect = mutableStateOf(false)
    val sideEffect: State<Boolean> = _sideEffect

    private val _isFavoriteState = mutableStateOf<IsFavoriteState>(IsFavoriteState.NotFavorite)
    val isFavoriteState: State<IsFavoriteState> = _isFavoriteState

    private val _dialogState = mutableStateOf<DialogState>(DialogState.Close)
    val dialogState: MutableState<DialogState> = _dialogState

    private val _notPremiumDialog = mutableStateOf<DialogState>(DialogState.Close)
    val notPremiumDialog: MutableState<DialogState> = _notPremiumDialog

    private val _authState = mutableStateOf<UserState>(UserState.UnauthedUser)


    fun updateUiState() {
        savedStateHandle.get<String>(PARAM_COIN_ID)?.let { coinId ->
            getCoin(coinId)
            getChart(coinId, TimeRange.ONE_DAY)
        }
    }

    private fun getCoin(coinId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dashCoinRepository.getCoinByIdRemote(coinId).collect { result ->
                when (result) {
                    is Resource.Success -> {
                        _coinState.update { CoinState(coin = result.data) }
                        result.data?.toFavoriteCoin()?.run { isFavorite(this) }
                    }
                    is Resource.Error -> {
                        _coinState.update {  CoinState(
                            error = result.message ?: "Unexpected Error"
                        ) }
                    }
                    is Resource.Loading -> {
                        _coinState.update { CoinState(isLoading = true) }
                        delay(300)
                    }
                }
            }
        }
    }

    private fun getChart(coinId: String, period: TimeRange) {
        dashCoinRepository.getChartsDataRemote(coinId, getTimeSpanByTimeRange(period)).onEach { result ->
            when (result) {
                is Resource.Success -> {
                    val chartsEntry = mutableListOf<Entry>()

                    result.data?.let { charts ->
                        charts.chart?.forEach { value ->
                            chartsEntry.add(addEntry(value[0], value[1]))
                        }

                        _chartState.value = ChartState(chart = chartsEntry)
                    }
                }
                is Resource.Error -> {
                    _chartState.value = ChartState(
                        error = result.message ?: "Unexpected Error"
                    )
                }
                is Resource.Loading -> {
                    _chartState.value = ChartState(isLoading = true)
                }
            }
        }.launchIn(viewModelScope)
    }

    fun onTimeSpanChanged(
        timeRange: TimeRange
    ) {
       _coinState.value.coin?.id?.let { coinId ->
           getChart(
               coinId = coinId,
               period = timeRange
           )
       }
    }

    private fun getTimeSpanByTimeRange(timeRange: TimeRange): ChartTimeSpan =
        when (timeRange) {
            TimeRange.ONE_DAY -> ChartTimeSpan.TIMESPAN_1DAY
            TimeRange.ONE_WEEK -> ChartTimeSpan.TIMESPAN_1WEK
            TimeRange.ONE_MONTH -> ChartTimeSpan.TIMESPAN_1MONTH
            TimeRange.ONE_YEAR -> ChartTimeSpan.TIMESPAN_1YEAR
            TimeRange.ALL -> ChartTimeSpan.TIMESPAN_ALL
        }

    fun onEvent(events: FavoriteCoinEvents) {
        viewModelScope.launch(Dispatchers.IO) {
            when (events) {
                is FavoriteCoinEvents.AddCoin -> addFavoriteCoin(events.coin)
                is FavoriteCoinEvents.DeleteCoin -> deleteFavoriteCoin(events.coin)
            }
        }
    }

    private suspend fun addFavoriteCoin(coin: FavoriteCoin) {
        dashCoinRepository.addFavoriteCoin(coin)
        updateFavoriteCoinsCount()

        _isFavoriteState.value = IsFavoriteState.Favorite
        _favoriteMsg.value = IsFavoriteState.Messages(
            favoriteMessage = "${coin.name} successfully added to favorite! "
        )
    }

    private suspend fun deleteFavoriteCoin(coin: FavoriteCoin) {
        dashCoinRepository.removeFavoriteCoin(coin)
        updateFavoriteCoinsCount()

        _isFavoriteState.value = IsFavoriteState.NotFavorite
        _favoriteMsg.value = IsFavoriteState.Messages(
            notFavoriteMessage = "${coin.name} removed from favorite! "
        )
    }

    private fun updateFavoriteCoinsCount() {
        viewModelScope.launch(Dispatchers.IO) {
            dashCoinRepository.getDashCoinUser().firstOrNull()?.let { user ->
                dashCoinRepository.getFlowFavoriteCoins().collect {
                    val dashCoinUser = user.copy(favoriteCoinsCount = it.size )
                    dashCoinRepository.cacheDashCoinUser(dashCoinUser)
                }
            }
        }
    }

    private fun isFavorite(coin: FavoriteCoin) {
        viewModelScope.launch(Dispatchers.IO) {
            dashCoinUseCases.isFavoriteState(coin).let { result ->
                _isFavoriteState.value = result
            }
        }
    }

    fun updateUserState() {
        viewModelScope.launch {
            dashCoinUseCases.userStateProvider(
                function = {}
            ).collect { userState -> _authState.value = userState}
        }
    }

    private fun premiumLimit(coin: CoinById) {
        viewModelScope.launch {
            dashCoinRepository.getDashCoinUser().collect { result ->
                result?.let { user ->
                    when (user.isPremiumLimit()) {
                        false -> onEvent(FavoriteCoinEvents.AddCoin(coin.toFavoriteCoin()))
                        true -> _notPremiumDialog.value = DialogState.Open
                    }
                }
            }
        }
    }

    fun onFavoriteClick(
        coin: CoinById
    ) {
        when (_isFavoriteState.value) {
            is IsFavoriteState.Favorite -> {
                _dialogState.value = DialogState.Open
            }
            is IsFavoriteState.NotFavorite -> {
                when (_authState.value) {
                    is UserState.UnauthedUser -> _sideEffect.value = !_sideEffect.value
                    is UserState.AuthedUser -> premiumLimit(coin)
                    is UserState.PremiumUser -> onEvent(FavoriteCoinEvents.AddCoin(coin.toFavoriteCoin()))
                }
            }
        }
    }

    private fun addEntry(x: Float, y: Float) = Entry(x, y)
}