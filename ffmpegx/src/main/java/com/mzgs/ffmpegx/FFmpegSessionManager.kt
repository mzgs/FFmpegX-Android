package com.mzgs.ffmpegx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FFmpegSessionManager {
    
    private val _activeSessions = MutableStateFlow<Map<Long, SessionInfo>>(emptyMap())
    val activeSessions: StateFlow<Map<Long, SessionInfo>> = _activeSessions.asStateFlow()
    
    @Synchronized
    fun addSession(sessionId: Long, description: String, command: String) {
        val sessionInfo = SessionInfo(
            sessionId = sessionId,
            description = description,
            command = command,
            startTime = System.currentTimeMillis(),
            state = SessionState.RUNNING
        )
        _activeSessions.value = _activeSessions.value + (sessionId to sessionInfo)
    }
    
    @Synchronized
    fun updateSessionState(sessionId: Long, state: SessionState) {
        _activeSessions.value = _activeSessions.value.mapValues { (id, info) ->
            if (id == sessionId) {
                info.copy(state = state, endTime = System.currentTimeMillis())
            } else {
                info
            }
        }
    }
    
    @Synchronized
    fun updateSessionProgress(sessionId: Long, progress: Float) {
        _activeSessions.value = _activeSessions.value.mapValues { (id, info) ->
            if (id == sessionId) {
                info.copy(progress = progress)
            } else {
                info
            }
        }
    }
    
    @Synchronized
    fun updateSessionOutput(sessionId: Long, output: String) {
        _activeSessions.value = _activeSessions.value.mapValues { (id, info) ->
            if (id == sessionId) {
                info.copy(lastOutput = output)
            } else {
                info
            }
        }
    }
    
    @Synchronized
    fun removeSession(sessionId: Long) {
        _activeSessions.value = _activeSessions.value - sessionId
    }
    
    @Synchronized
    fun cancelSession(sessionId: Long): Boolean {
        val success = FFmpegExecutor.cancel(sessionId)
        if (success) {
            updateSessionState(sessionId, SessionState.CANCELLED)
        }
        return success
    }
    
    @Synchronized
    fun cancelAllSessions() {
        getRunningSessions().forEach { cancelSession(it.sessionId) }
    }
    
    @Synchronized
    fun isSessionRunning(sessionId: Long): Boolean {
        return FFmpegExecutor.isRunning(sessionId)
    }
    
    @Synchronized
    fun getSessionInfo(sessionId: Long): SessionInfo? {
        return _activeSessions.value[sessionId]
    }
    
    @Synchronized
    fun clearCompletedSessions() {
        _activeSessions.value = _activeSessions.value.filterValues { sessionInfo ->
            sessionInfo.state == SessionState.RUNNING
        }
    }
    
    fun getActiveSessionCount(): Int = getRunningSessionsCount()

    @Synchronized
    fun getRunningSessionsCount(): Int {
        return _activeSessions.value.count { it.value.state == SessionState.RUNNING }
    }
    
    @Synchronized
    fun getAllSessions(): List<SessionInfo> {
        return _activeSessions.value.values.toList()
    }
    
    @Synchronized
    fun getRunningSessions(): List<SessionInfo> {
        return _activeSessions.value.values.filter { it.state == SessionState.RUNNING }
    }
    
    enum class SessionState {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    data class SessionInfo(
        val sessionId: Long,
        val description: String,
        val command: String,
        val startTime: Long,
        val endTime: Long? = null,
        val state: SessionState,
        val progress: Float = 0f,
        val lastOutput: String = ""
    ) {
        val duration: Long
            get() = (endTime ?: System.currentTimeMillis()) - startTime
        
        val isRunning: Boolean
            get() = state == SessionState.RUNNING
        
        val isCompleted: Boolean
            get() = state == SessionState.COMPLETED
        
        val isFailed: Boolean
            get() = state == SessionState.FAILED
        
        val isCancelled: Boolean
            get() = state == SessionState.CANCELLED
        
        val formattedDuration: String
            get() = FFmpegUtils.formatDuration(duration)
        
        val progressPercentage: Int
            get() = (progress * 100).toInt()
    }
}