package com.github.scphamster.bluetoothConnectionsTester

import android.app.Application
import android.util.Log
import android.widget.Toast

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.circuit.Pin
import com.github.scphamster.bluetoothConnectionsTester.circuit.toResistance
import com.github.scphamster.bluetoothConnectionsTester.device.Director

import com.github.scphamster.bluetoothConnectionsTester.device.ControllerResponseInterpreter.Commands
import com.github.scphamster.bluetoothConnectionsTester.device.*
import kotlinx.coroutines.*

class DeviceControlViewModel(val app: Application) : AndroidViewModel(app) {
    companion object {
        private const val Tag = "ControlViewModel"
    }
    
    val errorHandler = ErrorHandler(app)
    val measurementsHandler: MeasurementsHandler
    val measurementsDirector: Director //    private val bluetooth: BluetoothBridge //    private val logger by lazy { ToFileLogger(app) }

    var maxDetectableResistance: Float = 0f
    
    private var isInitialized: Boolean
    
    init {
        isInitialized = false
        measurementsHandler = MeasurementsHandler(errorHandler, app, viewModelScope)
        measurementsDirector =
            Director(app, viewModelScope, errorHandler, measurementsHandler.boardsManager.boardsArrayChannel)
        
    }
    
    override fun onCleared() {
        Log.d(Tag, "Clearing ViewModel!")
        viewModelScope.cancel("End of viewModelScope")
        viewModelScope.coroutineContext.cancelChildren(CancellationException("End of viewModelScope"))
        super.onCleared()
    }
    
    fun setupViewModel(): Boolean {
        if (!isInitialized) {
            getPinoutConfigFile()
            setupVoltageLevel()
            setupMinimumResistance()

            isInitialized = true
            Log.v(Tag, "ViewModel set up")
        }
        return true
    }
    
    fun setupMinimumResistance() {
        val pref_manager = PreferenceManager.getDefaultSharedPreferences(app)
        val max_resistance =
            pref_manager.getString(PreferencesFragment.Companion.SharedPreferenceKey.MaximumResistance.text, "")
        max_resistance?.let {
            setMinimumResistanceToBeRecognizedAsConnection(it)
        }
    }
    
    fun setupVoltageLevel() {
        viewModelScope.launch {
            measurementsDirector.setVoltageLevelAccordingToPreferences()
        }
    }
    
    fun getPinoutConfigFile() {
        viewModelScope.launch {
            val workbook = viewModelScope.async {
                Storage.getWorkBookFromFile(app)
            }
            
            try {
                val workbook_instance = workbook.await()
                Log.d(Tag, "Workbook obtained, Not null? : ${workbook != null}")
                
                measurementsHandler.boardsManager.pinoutInterpreter.document = workbook_instance
                
                toast("Pinout descriptor found")
                measurementsHandler.boardsManager.fetchPinsInfoFromExcelToPins()
            }
            catch (e: Throwable) {
                e.message?.let {
                    Log.e(Tag, "Error while opening XLSX file: " + it)
                }
                errorHandler.handleError("Error while opening XLSX file: " + e.message)
            }
        }
    }
    
    fun storeMeasurementsToFile() = viewModelScope.launch {
        val job = viewModelScope.async(Dispatchers.Default) {
            measurementsHandler.resultsSaver.storeMeasurements(maxDetectableResistance)
            measurementsHandler.resultsSaver.storeExpectedToMeasuredDifferences(maxDetectableResistance)
        }
        
        try {
            job.join()
            toast("Successfully stored results to file!")
        }
        catch (e: Error) {
            errorHandler.handleError(e.message)
        }
        catch (e: Throwable) {
            errorHandler.handleError(e.message)
        }
    }
    
    fun checkConnections() {
        val if_sequential = PreferenceManager.getDefaultSharedPreferences(app)
            .getBoolean(PreferencesFragment.Companion.SharedPreferenceKey.SequentialModeScan.text, false)
        
        val domain = PreferenceManager.getDefaultSharedPreferences(app)
            .getString("connection_domain", "");
        
        val answer_domain = when (domain) {
            "Raw" -> Commands.CheckConnectivity.AnswerDomain.Raw
            "Voltage" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Resistance" -> Commands.CheckConnectivity.AnswerDomain.Resistance
            "SimpleBoolean" -> Commands.CheckConnectivity.AnswerDomain.SimpleConnectionFlag
            else -> Commands.CheckConnectivity.AnswerDomain.Raw
        }
        
        //        measurementsHandler.commander.sendCommand(Commands.CheckConnectivity(answer_domain, sequential = if_sequential))
        viewModelScope.launch {
            measurementsDirector.checkAllConnections(measurementsHandler.boardsManager.pinConnectivityResultsCh)
        }
    }
    
    fun checkConnections(for_pin: Pin) {
        val if_sequential = PreferenceManager.getDefaultSharedPreferences(app)
            .getBoolean(PreferencesFragment.Companion.SharedPreferenceKey.SequentialModeScan.text, false)
        
        val domain = PreferenceManager.getDefaultSharedPreferences(app)
            .getString("connection_domain", "");
        
        val answer_domain = when (domain) {
            "Raw" -> Commands.CheckConnectivity.AnswerDomain.Raw
            "Voltage" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Resistance" -> Commands.CheckConnectivity.AnswerDomain.Resistance
            "SimpleBoolean" -> Commands.CheckConnectivity.AnswerDomain.SimpleConnectionFlag
            else -> Commands.CheckConnectivity.AnswerDomain.Raw
        }
        
        //        measurementsHandler.commander.sendCommand(Commands.CheckConnectivity(answer_domain,
        //                                                                             for_pin.descriptor.pinAffinityAndId,
        //                                                                             if_sequential))
        //
        viewModelScope.launch {
            measurementsDirector.checkConnection(for_pin.descriptor.pinAffinityAndId,
                                               measurementsHandler.boardsManager.pinConnectivityResultsCh)
            Log.d(Tag, "check connection succeeded")
            
        }
    }
    
    fun setMinimumResistanceToBeRecognizedAsConnection(value_as_text: String) {
        val resistance = value_as_text.toResistance()
        
        resistance?.let {
            maxDetectableResistance = resistance.value
            measurementsHandler.boardsManager.maxResistanceAsConnection = maxDetectableResistance
        }
    }

    private fun toast(msg: String?) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG)
            .show()
    }
}

