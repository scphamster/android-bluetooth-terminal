package com.github.scphamster.bluetoothConnectionsTester.device

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.circuit.PinAffinityAndId
import com.github.scphamster.bluetoothConnectionsTester.circuit.SimpleConnection
import com.github.scphamster.bluetoothConnectionsTester.circuit.SimpleConnectivityDescription
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

//todo: add check for duplicate board addresses with multiple controllers
//todo: when all controllers disconnects give IoBoardsManager empty list of controllers
class Director(
    val app: Application,
    val scope: CoroutineScope,
    private val errorHandler: ErrorHandler,
    private val boardsArrayChannel: Channel<Array<IoBoard>>
) {
    companion object {
        private const val Tag = "Director"
        private const val CHANNEL_SIZE = 20 // should be higher than number of IO boards in the system in overall
        private const val WAIT_FOR_NEW_SOCKETS_TIMEOUT = 4_000.toLong()
        private const val STD_ENTRY_SOCKET_PORT = 1500
        private const val STD_ENTRY_SOCKET_TIMEOUT_MS = 4000
        private const val KEEPALIVE_MSG_PERIOD_MS = 1000
    }

    private inner class NewSocketRegistator(val socketChannel: Channel<DeviceLink>) {
        init {
            scope.launch(Dispatchers.Default + CoroutineName("SocketRegistrator")) {

                while (isActive) {
                    val entrySocketJob = async {
                        run()
                    }

                    try {
                        entrySocketJob.await()
                    } catch (e: CancellationException) {
                        Log.d(Tag, "cancelled due to: $e")
                    } catch (e: Exception) {
                        Log.e(Tag, "Unexpected exception: $e")
                    } finally {
                        if (::serverSocket.isInitialized && !serverSocket.isClosed) {
                            withContext(Dispatchers.IO) {
                                serverSocket.close()
                            }
                        }
                    }
                }
            }
        }

        lateinit var socket: Socket
        lateinit var serverSocket: ServerSocket

        suspend fun run() = withContext(Dispatchers.IO) {
            val Tag = Tag + ":EntrySocket"
            val keepAliveMessage = WorkSocket.KeepAliveMessage(
                KeepAliveMessage().serialize(),
                MessageFromController.KeepAlive.KEEPALIVE_TIMEOUT_MS / 4
            )

            while (isActive) {
                Log.w(Tag, "Restart")
                try {
                    serverSocket = ServerSocket(STD_ENTRY_SOCKET_PORT)
                    serverSocket.soTimeout = STD_ENTRY_SOCKET_TIMEOUT_MS
                    Log.v(Tag, "Waiting for new client")
                    socket = serverSocket.accept()
                } catch (e: SocketTimeoutException) {
                    Log.d(Tag, "serverSocket timeout!")
                    if (::serverSocket.isInitialized && !serverSocket.isClosed) serverSocket.close()
                    yield()
                    continue
                } catch (e: Exception) {
                    Log.e(Tag, "Unexpected error in server socket! ${e.message}")
                    if (::serverSocket.isInitialized && !serverSocket.isClosed) serverSocket.close()
                    yield()
                    continue
                }

                Log.d(Tag, "Someone connected: ${socket.remoteSocketAddress}")

                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                val newSocket = WorkSocket(keepAliveMessage)

                socketChannel.send(newSocket)

                while (newSocket.port == -1) {
                    yield()
                    continue
                }

                Log.d(Tag, "New controller socket started! Port: ${newSocket.port}")

                outputStream.write(newSocket.port.toByteArray())  // writing new port number to controller
                val msg = ReadAnswer(inputStream)

                if (msg == null) {
                    Log.e(Tag, "New socket: answer not obtained! Restarting entry server!")
                } else {
                    when (msg) {
                        is Msg.OperationConfirmation -> {
                            Log.d(Tag, "New socket: confirmation: ${msg.confirmationValue}")
                        }
                    }
                }
                serverSocket.close()
            }
        }

        suspend fun ReadAnswer(inStream: java.io.InputStream, timeout: Long = Long.MAX_VALUE) =
            withContext<Msg?>(Dispatchers.IO) {
                val deadline = if (timeout == Long.MAX_VALUE) Long.MAX_VALUE
                else timeout

                while (inStream.available() == 0) {
                    if (deadline < System.currentTimeMillis()) {
                        Log.e(Tag, "Timeout of answer retrieval!")
                        return@withContext null
                    }
                }

                val buffer = ByteArray(inStream.available() + 64)
                inStream.read(buffer)
                val msg = Msg.deserialize(buffer)

                return@withContext msg
            }
    }

    enum class State {
        InitializingDirector,
        SearchingForControllers,
        RecoveryFromFailure,
        UpdatingBoards,
        NoBoardsAvailable,
        Operating
    }

    inner class MachineState {
        val ready: Boolean
            get() = state.value == State.Operating

        val state = MutableLiveData<State>(State.InitializingDirector)
    }

    private class ControllersCommonSettings {
        var voltageLevel = IoBoardsManager.VoltageLevel.Low
            set(newVal) {
                synchronized(this) {
                    field = newVal
                }
            }
            get() {
                val value = synchronized(this) {
                    field
                }
                return value
            }
    }

    val controllers = CopyOnWriteArrayList<ControllerManager>()
    val machineState = MachineState()

    private lateinit var updateAvailableBoardsJob: Job

    private val newDeviceLinksChannel = Channel<DeviceLink>(CHANNEL_SIZE)
    private val newSocketRegistator = NewSocketRegistator(newDeviceLinksChannel)
    private val controllersSettings = ControllersCommonSettings()

    init {
        scope.launch {
            newDeviceLinksReceiverTask()
        }

        Log.v(Tag, "Director initialized!")
    }

    //measurement functions
    suspend fun checkAllConnections(connectionsChannel: Channel<SimpleConnectivityDescription>) =
        withContext(Dispatchers.Default) {
            val operableControllers = getAllOperableControllers()

            when (operableControllers.size) {
                0 -> {
                    Log.e(Tag, "Check all connections command invoked with no controllers available")
                    errorHandler.handleError("No controllers to operate with!")
                }
                1 -> {
                    operableControllers.iterator()
                        .next()
                        .checkConnectionsForLocalBoards(connectionsChannel)
                }
                else -> {
                    getAllBoards().forEach { b ->
                        b.pins.forEach {
                            checkConnection(it.descriptor.pinAffinityAndId, connectionsChannel)
                        }
                    }
                }
            }
        }

    suspend fun checkConnection(
        pinAffinityAndId: PinAffinityAndId,
        connectionsChannel: Channel<SimpleConnectivityDescription>
    ) =
        withContext(Dispatchers.Default) {
            Log.v(Tag, "Checking single connection: $pinAffinityAndId")

            val controller =
                getAllBoards().find { b -> b.address == pinAffinityAndId.boardAddress }?.belongsToController?.get()

            Log.v(Tag, "Setting voltage")

            if (controller?.setVoltageAtPin(pinAffinityAndId.getPhysicalPinAffinityAndID()) != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "check connection failed due to failed set voltage!")
                return@withContext
            }

            Log.v(Tag, "Voltage setting successful, measuring all voltages")

            val allBoardsVoltages = controllers.map { controller ->
                val allVoltages = try {
                    controller.measureAllVoltages()
                } catch (e: Exception) {
                    Log.e(Tag, "Exception caught in check connection: $e")
                    return@withContext
                }

                if (allVoltages == null) {
                    Log.e(Tag, "null instead of voltage table!")
                    return@withContext
                }

                allVoltages
            }
                .flatMap { it.toList() }

            val disableResult = controller.disableOutput()
            if (disableResult != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "Disable output command failed with result: $disableResult")
                errorHandler.handleError("Failed to disable output for controller: $controller")
                return@withContext
            }

            Log.v(Tag, "Check connection succeeded")

            allBoardsVoltages.forEach() {
                Log.v(Tag, "Board: ${it.boardAddress}")
                it.voltages.forEach { Log.v(Tag, "${it.first} : ${it.second.value}") }
            }

            val simpleConnections = allBoardsVoltages.map { boardAndVoltages ->
                val boardAddress = boardAndVoltages.boardAddress

                boardAndVoltages.voltages.filter { it.second.value > 0 }
                    .map { pinAndVoltage ->
                        SimpleConnection(PinAffinityAndId(boardAddress, pinAndVoltage.first), pinAndVoltage.second)
                    }
            }
                .flatMap { it }

            connectionsChannel.send(SimpleConnectivityDescription(pinAffinityAndId, simpleConnections.toTypedArray()))
        }

    private suspend fun getAllBoards() = withContext(Dispatchers.Default) {
        val deferredBoards = getAllOperableControllers().map { controller -> async { controller.getBoards() } }

        val boardsArrays = try {
            deferredBoards.awaitAll()
        } catch (e: Exception) {
            Log.e(Tag, "get all boards command failed with exception: ${e.message}")
            return@withContext emptyArray<IoBoard>()
        }

        return@withContext boardsArrays.filterNot { it.isEmpty() }
            .flatMap { it.toList() }
            .toTypedArray()
    }

    suspend fun setVoltageLevelAccordingToPreferences() = withContext(Dispatchers.Default) {
        val selected_voltage_level = PreferenceManager.getDefaultSharedPreferences(app)
            .getString("output_voltage_level", "")

        val new_voltage_level = when (selected_voltage_level) {
            "Low(0.7V)" -> IoBoardsManager.VoltageLevel.Low
            "High(1.0V)" -> IoBoardsManager.VoltageLevel.High
            else -> IoBoardsManager.VoltageLevel.Low
        }

        val voltageChangeJobs = arrayListOf<Deferred<ControllerResponse>>()
        for (controller in controllers) {
            voltageChangeJobs.add(scope.async {
                controller.setVoltageLevel(new_voltage_level)
            })
        }

        val results = try {
            voltageChangeJobs.awaitAll()
        } catch (e: Exception) {
            Log.e(Tag, "Exception caught in set voltage level according to preferences")
            return@withContext
        }
        for (result in results) {
            if (result != ControllerResponse.CommandPerformanceSuccess) {
                Log.e(Tag, "Command not successful!")
                return@withContext
            }
        }

        controllersSettings.voltageLevel = new_voltage_level
    }

    private suspend fun updateAllBoards() = withContext(Dispatchers.Default) {
        withContext(Dispatchers.Main) {
            machineState.state.value = State.UpdatingBoards
        }

        val allBoards = getAllBoards()
        boardsArrayChannel.send(allBoards)

        if (allBoards.isEmpty()) {
            Log.e(Tag, "No boards found!")
            withContext(Dispatchers.Main) {
                machineState.state.value = State.NoBoardsAvailable
            }
        } else {
            Log.d(Tag, "Found ${allBoards.size} boards!")
            withContext(Dispatchers.Main) {
                machineState.state.value = State.Operating
            }
        }
    }

    private suspend fun newDeviceLinksReceiverTask() = withContext(Dispatchers.Default) {
        // go to UI thread while changing the observed value
        withContext(Dispatchers.Main) {
            machineState.state.value = State.SearchingForControllers
        }

        while (isActive) {
            val waitForAllDevicesToConnectTimeoutJob = async {
                delay(WAIT_FOR_NEW_SOCKETS_TIMEOUT)

                if (isActive) {
                    if (controllers.size == 0) return@async
                }
            }

            val newLink = newDeviceLinksChannel.receiveCatching().getOrNull()
            waitForAllDevicesToConnectTimeoutJob.cancel()

            if (newLink == null) {
                Log.e(Tag, "New WorkSocket arrived but it is NULL!")
                delay(500)
                continue
            }

            Log.d(Tag, "New socket arrived!")

            val selectedVoltageLevel = PreferenceManager.getDefaultSharedPreferences(app)
                .getString("output_voltage_level", "")

            val newVoltageLevel = when (selectedVoltageLevel) {
                "Low(0.7V)" -> IoBoardsManager.VoltageLevel.Low
                "High(1.0V)" -> IoBoardsManager.VoltageLevel.High
                else -> IoBoardsManager.VoltageLevel.Low
            }

            val uuid = UUID.randomUUID()
            val controller =
                ControllerManager(uuid, newLink, scope, newVoltageLevel,
                    onStateChangeCallback = { prevState, newState ->
                        when (newState) {
                            ControllerManagerI.State.Operating -> startUpdateBoardsJob()

                            else -> {
                                if (prevState == ControllerManagerI.State.Operating) startUpdateBoardsJob()
                            }
                        }
                    },
                    onFatalErrorCallback = {
                        controllers.removeIf() {
                            if (it.uuid == uuid) {
                                Log.e(Tag, "Controller is removed due to fatal error")
                                it.cancelAllJobs()
                                true
                            } else false
                        }

                        if (controllers.size == 0) {
                            scope.launch(Dispatchers.Main) {
                                machineState.state.value = State.SearchingForControllers
                            }
                        } else {
                            scope.launch(Dispatchers.Main) {
                                machineState.state.value = State.RecoveryFromFailure
                            }
                        }

                        startUpdateBoardsJob()
                    })

            controllers.add(controller)
        }

        Log.e(Tag, "New sockets receiver task ended!")
    }

    private fun startUpdateBoardsJob() {
        // we need to cancel retrieval of connected boards because new controller was found
        if (::updateAvailableBoardsJob.isInitialized) {
            updateAvailableBoardsJob.cancel("other controller changed its state to 'operating'")
        }

        updateAvailableBoardsJob = scope.launch(Dispatchers.Default) {
            // make sure the update is happening when all controllers have connected
            delay(3000)
            if (isActive) {
                Log.d(Tag, "Updating all boards!")
                updateAllBoards()
            }
        }
    }

    private fun getAllOperableControllers(): Collection<ControllerManagerI> {
        return controllers.filter() {
            it.state == ControllerManagerI.State.Operating
        }
    }
}