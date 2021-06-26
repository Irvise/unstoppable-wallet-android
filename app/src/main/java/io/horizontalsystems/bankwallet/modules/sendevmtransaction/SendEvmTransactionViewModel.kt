package io.horizontalsystems.bankwallet.modules.sendevmtransaction

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.*
import io.horizontalsystems.bankwallet.core.ethereum.EvmCoinServiceFactory
import io.horizontalsystems.bankwallet.core.providers.Translator
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.modules.sendevm.SendEvmData
import io.horizontalsystems.bankwallet.modules.transactions.transactionInfo.TransactionInfoAddressMapper
import io.horizontalsystems.core.toHexString
import io.horizontalsystems.erc20kit.decorations.ApproveMethodDecoration
import io.horizontalsystems.erc20kit.decorations.TransferMethodDecoration
import io.horizontalsystems.ethereumkit.decorations.ContractMethodDecoration
import io.horizontalsystems.ethereumkit.decorations.RecognizedMethodDecoration
import io.horizontalsystems.ethereumkit.decorations.UnknownMethodDecoration
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.horizontalsystems.uniswapkit.decorations.SwapMethodDecoration
import io.reactivex.disposables.CompositeDisposable
import java.math.BigInteger

class SendEvmTransactionViewModel(
        private val service: ISendEvmTransactionService,
        private val coinServiceFactory: EvmCoinServiceFactory
) : ViewModel() {
    private val disposable = CompositeDisposable()

    val sendEnabledLiveData = MutableLiveData<Boolean>()
    val errorLiveData = MutableLiveData<String?>()

    val sendingLiveData = MutableLiveData<Unit>()
    val sendSuccessLiveData = MutableLiveData<ByteArray>()
    val sendFailedLiveData = MutableLiveData<String>()

    val viewItemsLiveData = MutableLiveData<List<SectionViewItem>>()

    init {
        service.stateObservable.subscribeIO { sync(it) }.let { disposable.add(it) }
        service.txDataStateObservable.subscribeIO { sync(it) }.let { disposable.add(it) }
        service.sendStateObservable.subscribeIO { sync(it) }.let { disposable.add(it) }

        sync(service.state)
        sync(service.txDataState)
        sync(service.sendState)
    }

    fun send(logger: AppLogger) {
        service.send(logger)
    }

    private fun sync(state: SendEvmTransactionService.State) =
            when (state) {
                SendEvmTransactionService.State.Ready -> {
                    sendEnabledLiveData.postValue(true)
                    errorLiveData.postValue(null)
                }
                is SendEvmTransactionService.State.NotReady -> {
                    sendEnabledLiveData.postValue(false)
                    errorLiveData.postValue(state.errors.firstOrNull()?.let { convertError(it) })
                }
            }

    private fun sync(txDataState: DataState<SendEvmTransactionService.TxDataState>) {
        val decoration = txDataState.dataOrNull?.decoration
        val transactionData = txDataState.dataOrNull?.transactionData

        val viewItems = if (decoration != null && transactionData != null) {
            getViewItems(decoration, transactionData, txDataState.dataOrNull?.additionalInfo)
        } else if (transactionData != null) {
            getFallbackViewItems(transactionData)
        } else {
            return
        }

        viewItems?.let {
            viewItemsLiveData.postValue(it)
        }
    }

    private fun sync(sendState: SendEvmTransactionService.SendState) =
            when (sendState) {
                SendEvmTransactionService.SendState.Idle -> Unit
                SendEvmTransactionService.SendState.Sending -> {
                    sendEnabledLiveData.postValue(false)
                    sendingLiveData.postValue(Unit)
                }
                is SendEvmTransactionService.SendState.Sent -> sendSuccessLiveData.postValue(sendState.transactionHash)
                is SendEvmTransactionService.SendState.Failed -> sendFailedLiveData.postValue(convertError(sendState.error))
            }

    private fun getViewItems(decoration: ContractMethodDecoration, transactionData: TransactionData, additionalInfo: SendEvmData.AdditionalInfo?): List<SectionViewItem>? =
            when (decoration) {
                is TransferMethodDecoration -> getEip20TransferViewItems(decoration.to, decoration.value, transactionData.to, additionalInfo)
                is ApproveMethodDecoration -> getEip20ApproveViewItems(decoration.spender, decoration.value, transactionData.to)
                is SwapMethodDecoration -> getSwapViewItems(decoration.trade, decoration.tokenIn, decoration.tokenOut, decoration.to, decoration.deadline, additionalInfo)
                is RecognizedMethodDecoration -> getRecognizedMethodItems(transactionData, decoration.method, decoration.arguments)
                is UnknownMethodDecoration -> getUnknownMethodItems(transactionData)
                else -> null
            }

    private fun getEip20TransferViewItems(to: Address, value: BigInteger, contractAddress: Address, additionalInfo: SendEvmData.AdditionalInfo?): List<SectionViewItem>? {
        val coinService = coinServiceFactory.getCoinService(contractAddress) ?: return null

        val viewItems = mutableListOf(
                ViewItem.Subhead(Translator.getString(R.string.Send_Confirmation_YouSend), coinService.coin.title),
                ViewItem.Value(Translator.getString(R.string.Send_Confirmation_Amount), coinService.amountData(value).getFormatted(), ValueType.Outgoing)
        )
        val addressValue = to.eip55
        val addressTitle = additionalInfo?.sendInfo?.domain ?: TransactionInfoAddressMapper.map(addressValue)
        viewItems.add(
                ViewItem.Address(Translator.getString(R.string.Send_Confirmation_To), addressTitle, value = addressValue)
        )

        return listOf(SectionViewItem(viewItems))
    }

    private fun getEip20ApproveViewItems(spender: Address, value: BigInteger, contractAddress: Address): List<SectionViewItem>? {
        val coinService = coinServiceFactory.getCoinService(contractAddress) ?: return null

        val addressValue = spender.eip55
        val addressTitle = TransactionInfoAddressMapper.map(addressValue)

        val viewItems = listOf(
                ViewItem.Subhead(Translator.getString(R.string.Approve_YouApprove), coinService.coin.title),
                ViewItem.Value(Translator.getString(R.string.Send_Confirmation_Amount), coinService.amountData(value).getFormatted(), ValueType.Regular),
                ViewItem.Address(Translator.getString(R.string.Approve_Spender), addressTitle, addressValue)
        )

        return listOf(SectionViewItem(viewItems))
    }

    private fun getSwapViewItems(
            trade: SwapMethodDecoration.Trade,
            tokenIn: SwapMethodDecoration.Token,
            tokenOut: SwapMethodDecoration.Token,
            to: Address,
            deadline: BigInteger,
            additionalInfo: SendEvmData.AdditionalInfo?
    ): List<SectionViewItem>? {

        val coinServiceIn = getCoinService(tokenIn) ?: return null
        val coinServiceOut = getCoinService(tokenOut) ?: return null

        val info = additionalInfo?.swapInfo
        val sections = mutableListOf<SectionViewItem>()

        when (trade) {
            is SwapMethodDecoration.Trade.ExactIn -> {
                sections.add(SectionViewItem(listOf(
                        ViewItem.Subhead(Translator.getString(R.string.Swap_FromAmountTitle), coinServiceIn.coin.title),
                        ViewItem.Value(Translator.getString(R.string.Send_Confirmation_Amount), coinServiceIn.amountData(trade.amountIn).getFormatted(), ValueType.Outgoing)
                )))
                sections.add(SectionViewItem(listOf(
                        ViewItem.Subhead(Translator.getString(R.string.Swap_ToAmountTitle), coinServiceOut.coin.title),
                        getEstimatedSwapAmount(info?.let { coinServiceOut.amountData(it.estimatedOut).getFormatted() }, ValueType.Incoming),
                        ViewItem.Value(Translator.getString(R.string.Swap_Confirmation_Guaranteed), coinServiceOut.amountData(trade.amountOutMin).getFormatted(), ValueType.Regular)
                )))
            }
            is SwapMethodDecoration.Trade.ExactOut -> {
                sections.add(SectionViewItem(listOf(
                        ViewItem.Subhead(Translator.getString(R.string.Swap_FromAmountTitle), coinServiceIn.coin.title),
                        getEstimatedSwapAmount(info?.let { coinServiceOut.amountData(it.estimatedIn).getFormatted() }, ValueType.Outgoing),
                        ViewItem.Value(Translator.getString(R.string.Swap_Confirmation_Maximum), coinServiceIn.amountData(trade.amountInMax).getFormatted(), ValueType.Regular)
                )))
                sections.add(SectionViewItem(listOf(
                        ViewItem.Subhead(Translator.getString(R.string.Swap_ToAmountTitle), coinServiceOut.coin.title),
                        ViewItem.Value(Translator.getString(R.string.Swap_Confirmation_Guaranteed), coinServiceOut.amountData(trade.amountOut).getFormatted(), ValueType.Regular)
                )))
            }
        }

        val otherViewItems = mutableListOf<ViewItem>()
        info?.slippage?.let {
            otherViewItems.add(ViewItem.Value(Translator.getString(R.string.SwapSettings_SlippageTitle), it, ValueType.Regular))
        }
        info?.deadline?.let {
            otherViewItems.add(ViewItem.Value(Translator.getString(R.string.SwapSettings_DeadlineTitle), it, ValueType.Regular))
        }
        if (to != service.ownAddress) {
            val addressValue = to.eip55
            val addressTitle = info?.recipientDomain
                    ?: TransactionInfoAddressMapper.map(addressValue)
            otherViewItems.add(ViewItem.Address(Translator.getString(R.string.SwapSettings_RecipientAddressTitle), addressTitle, addressValue))
        }
        info?.price?.let {
            otherViewItems.add(ViewItem.Value(Translator.getString(R.string.Swap_Price), it, ValueType.Regular))
        }
        info?.priceImpact?.let {
            otherViewItems.add(ViewItem.Value(Translator.getString(R.string.Swap_PriceImpact), it, ValueType.Regular))
        }
        if (otherViewItems.isNotEmpty()) {
            sections.add(SectionViewItem(otherViewItems))
        }

        return sections
    }

    private fun getRecognizedMethodItems(transactionData: TransactionData, method: String, arguments: List<Any>): List<SectionViewItem>? {
        val addressValue = transactionData.to.eip55

        val viewItems = mutableListOf(
                ViewItem.Value(Translator.getString(R.string.Send_Confirmation_Amount), coinServiceFactory.baseCoinService.amountData(transactionData.value).getFormatted(), ValueType.Outgoing),
                ViewItem.Address(Translator.getString(R.string.Send_Confirmation_To), addressValue, addressValue),
                ViewItem.Subhead(Translator.getString(R.string.Send_Confirmation_Method), method),
                ViewItem.Input(transactionData.input.toHexString())
        )

        return listOf(SectionViewItem(viewItems))
    }

    private fun getUnknownMethodItems(transactionData: TransactionData): List<SectionViewItem>? {
        val addressValue = transactionData.to.eip55

        val viewItems = mutableListOf(
                ViewItem.Value(Translator.getString(R.string.Send_Confirmation_Amount), coinServiceFactory.baseCoinService.amountData(transactionData.value).getFormatted(), ValueType.Outgoing),
                ViewItem.Address(Translator.getString(R.string.Send_Confirmation_To), addressValue, addressValue),
                ViewItem.Input(transactionData.input.toHexString())
        )

        return listOf(SectionViewItem(viewItems))
    }

    private fun getEstimatedSwapAmount(value: String?, type: ValueType): ViewItem {
        val title = Translator.getString(R.string.Swap_Confirmation_Estimated)
        return value?.let { ViewItem.Value(title, it, type) }
                ?: ViewItem.Value(title, Translator.getString(R.string.NotAvailable), ValueType.Disabled)
    }

    private fun getCoinService(token: SwapMethodDecoration.Token) = when (token) {
        SwapMethodDecoration.Token.EvmCoin -> coinServiceFactory.baseCoinService
        is SwapMethodDecoration.Token.Eip20Coin -> coinServiceFactory.getCoinService(token.address)
    }

    private fun getFallbackViewItems(transactionData: TransactionData): List<SectionViewItem> {
        val addressValue = transactionData.to.eip55
        val viewItems = listOf(
                ViewItem.Value(Translator.getString(R.string.Send_Confirmation_Amount), coinServiceFactory.baseCoinService.amountData(transactionData.value).getFormatted(), ValueType.Outgoing),
                ViewItem.Address(Translator.getString(R.string.Send_Confirmation_To), addressValue, addressValue),
                ViewItem.Input(transactionData.input.toHexString())
        )
        return listOf(SectionViewItem(viewItems))
    }

    private fun convertError(error: Throwable) =
            when (val convertedError = error.convertedError) {
                is SendEvmTransactionService.TransactionError.InsufficientBalance -> {
                    Translator.getString(R.string.EthereumTransaction_Error_InsufficientBalance, coinServiceFactory.baseCoinService.coinValue(convertedError.requiredBalance).getFormatted())
                }
                is EvmError.InsufficientBalanceWithFee,
                is EvmError.ExecutionReverted -> {
                    Translator.getString(R.string.EthereumTransaction_Error_InsufficientBalanceForFee, coinServiceFactory.baseCoinService.coin.code)
                }
                else -> convertedError.message ?: convertedError.javaClass.simpleName
            }
}

data class SectionViewItem(
        val viewItems: List<ViewItem>
)

sealed class ViewItem {
    class Subhead(val title: String, val value: String) : ViewItem()
    class Value(val title: String, val value: String, val type: ValueType) : ViewItem()
    class Address(val title: String, val valueTitle: String, val value: String) : ViewItem()
    class Input(val value: String) : ViewItem()
}

enum class ValueType {
    Regular, Disabled, Outgoing, Incoming
}
