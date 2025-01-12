package com.github.scphamster.bluetoothConnectionsTester.circuit

import java.lang.ref.WeakReference
import java.util.*

interface PinIdentifier {
    /**
     * @brief composes affinity and id into pattern like affinity:id example: 37:1
     */
    fun getPrettyName(): String
    public val pinAffinityAndId: PinAffinityAndId
}

data class PinAffinityAndId(val boardAddress: BoardAddrT, val pinID: PinNumT) : PinIdentifier {
    companion object {
        const val MIN_BOARD_ADDRESS = 1
        const val MAX_BOARD_ADDRESS = 127
        const val PIN_ID_MIN = 0
        const val PIN_ID_MAX = 31
        const val SIZE_BYTES = 2
        private val harnessToLogicalPinMap = arrayOf(6,
                                                     4,
                                                     2,
                                                     0,
                                                     9,
                                                     11,
                                                     13,
                                                     15,
                                                     22,
                                                     20,
                                                     18,
                                                     16,
                                                     25,
                                                     27,
                                                     29,
                                                     31,
                                                     30,
                                                     28,
                                                     26,
                                                     24,
                                                     17,
                                                     19,
                                                     21,
                                                     23,
                                                     14,
                                                     12,
                                                     10,
                                                     8,
                                                     1,
                                                     3,
                                                     5,
                                                     7)
        
        fun deserialize(byteIterator: Iterator<Byte>): PinAffinityAndId {
            val board = byteIterator.next()
                .toUByte()
                .toInt()
            val id = byteIterator.next()
                .toUByte()
                .toInt()
            
            if ((board < MIN_BOARD_ADDRESS) || (board > MAX_BOARD_ADDRESS)) throw (IllegalArgumentException("board number($board) is out of range!"))
            
            if ((id < PIN_ID_MIN) || (id > PIN_ID_MAX)) throw (IllegalArgumentException("idx($id) is out of range!"))
            
            return PinAffinityAndId(board, id)
        }
    }
    
    override fun getPrettyName(): String {
        return "$boardAddress:$pinID"
    }
    
    override val pinAffinityAndId: PinAffinityAndId
        get() = PinAffinityAndId(boardAddress, pinID)
    
    fun getPhysicalOnBoardPinNum(): PinNumT {
        if (pinID >= IoBoard.PINS_COUNT_ON_SINGLE_BOARD) throw IllegalArgumentException("Pin number is higher than pin count on board: ${pinID}")
        
        return harnessToLogicalPinMap.get(pinID)
    }
    
    fun getPhysicalPinAffinityAndID(): PinAffinityAndId {
        return PinAffinityAndId(boardAddress, harnessToLogicalPinMap.get(pinID))
    }
}

data class PinDescriptor(val affinityAndId: PinAffinityAndId, var name: String? = null, var group: PinGroup? = null) :
    PinIdentifier {
    val UID: UUID = UUID.randomUUID()
    
    override val pinAffinityAndId: PinAffinityAndId
        get() = affinityAndId
    
    fun clearPinAndGroupNames() {
        group?.let {
            group = PinGroup(it.id, null)
        }
        
        name = null
    }
    
    fun getMuxNum(): Int {
        if (affinityAndId.getPhysicalOnBoardPinNum() >= IoBoard.SINGLE_MUX_PINS_SIZE) return 1
        else return 0
    }
    
    override fun getPrettyName(): String {
        val string_builder = StringBuilder()
        
        if (group != null) {
            string_builder.append(group?.getPrettyName())
        }
        else {
            string_builder.append(affinityAndId.boardAddress.toString())
        }
        
        string_builder.append(":")
        
        if (name != null) {
            string_builder.append(name)
        }
        else {
            string_builder.append(affinityAndId.pinID.toString())
        }
        
        return string_builder.toString()
    }
}

class Pin(val descriptor: PinDescriptor,
          var belongsToBoard: WeakReference<IoBoard> = WeakReference<IoBoard>(null),
          var connectionsListChangedFromPreviousCheck: Boolean = false,
          var inResistance: ResistanceT? = null,
          var outResistance: ResistanceT? = null,
          var shuntResistance: ResistanceT? = null,
          var outVoltage: VoltageT? = null) {
    var connections: MutableList<Connection> = mutableListOf()
        set(connections) {
            field = connections
            isHealthy = hasConnection(descriptor.pinAffinityAndId)
            expectedConnections?.let { expected ->
                notPresentExpectedConnections = expected.filter {
                    !hasConnection(it)
                }
                
                unexpectedConnections = field.filter { some_present_connection ->
                    (some_present_connection.toPin.pinAffinityAndId != descriptor.pinAffinityAndId) && (expected.find { some_present_connection.toPin.pinAffinityAndId == it.toPin.pinAffinityAndId } == null)
                    
                }
            }
        }
    var expectedConnections: List<Connection>? = null
    var unexpectedConnections: List<Connection>? = null
    var notPresentExpectedConnections: List<Connection>? = null
    var isHealthy = false
        private set
    
    fun hasConnection(connection: Connection): Boolean {
        if (connections.isEmpty()) return false
        
        return connections.find { some_connection ->
            some_connection.toPin.pinAffinityAndId == connection.toPin.pinAffinityAndId
        } != null
    }
    
    fun hasConnection(searched_pin_affinity_and_id: PinIdentifier): Boolean {
        if (connections.isEmpty()) return false
        
        val connection = connections.find { some_connection ->
            some_connection.toPin.pinAffinityAndId == searched_pin_affinity_and_id
        }
        
        return connection != null
    }
    
    fun getConnection(searched_pin_affinity_and_id: PinIdentifier): Connection? {
        if (connections.isEmpty()) return null
        
        return connections.find { some_connection ->
            some_connection.toPin.pinAffinityAndId == searched_pin_affinity_and_id
        }
    }
    
    fun checkIfConnectionsListIsDifferent(checked_connections: List<Connection>, maxResistance: Float = 0f): Boolean {
        
        val connections_not_in_my_list = checked_connections.filter {
            !hasConnection(it)
        }
        
        for (connection in connections_not_in_my_list) {
            if (connection.resistance != null) {
                if (connection.resistance.value < maxResistance) return true
            }
            else return true
        }
        
        val connections_not_in_checked = connections.filter { my_connection ->
            checked_connections.find {
                my_connection.toPin == it.toPin
            } == null
        }
        
        for (connection in connections_not_in_checked) {
            if (connection.resistance != null) {
                if (connection.resistance.value < maxResistance) return true
            }
            else return true
        }
        
        return false
    }
}