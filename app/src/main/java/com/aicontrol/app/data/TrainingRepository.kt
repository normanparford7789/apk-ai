package com.aicontrol.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrainingRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.trainingDao()

    val allTasks = dao.getAllTasks()
    val allHistory = dao.getAllHistory()

    suspend fun insertTask(task: TrainingTask): Long = withContext(Dispatchers.IO) {
        dao.insertTask(task)
    }

    suspend fun updateTask(task: TrainingTask) = withContext(Dispatchers.IO) {
        dao.updateTask(task)
    }

    suspend fun deleteTask(task: TrainingTask) = withContext(Dispatchers.IO) {
        dao.deleteTask(task)
    }

    suspend fun getTaskById(id: Long): TrainingTask? = withContext(Dispatchers.IO) {
        dao.getTaskById(id)
    }

    suspend fun setFavorite(id: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(id, isFavorite)
    }

    suspend fun incrementRunCount(id: Long) = withContext(Dispatchers.IO) {
        dao.incrementRunCount(id)
    }

    suspend fun incrementSuccessCount(id: Long) = withContext(Dispatchers.IO) {
        dao.incrementSuccessCount(id)
    }

    suspend fun insertHistory(history: ActionHistory) = withContext(Dispatchers.IO) {
        dao.insertHistory(history)
    }

    fun getHistoryForTask(taskId: Long) = dao.getHistoryForTask(taskId)

    suspend fun clearOldHistory() = withContext(Dispatchers.IO) {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        dao.deleteOldHistory(thirtyDaysAgo)
    }

    suspend fun getCategories(): List<String> = withContext(Dispatchers.IO) {
        dao.getCategories()
    }

    companion object {
        @Volatile
        private var instance: TrainingRepository? = null

        fun getInstance(context: Context): TrainingRepository {
            return instance ?: synchronized(this) {
                instance ?: TrainingRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
