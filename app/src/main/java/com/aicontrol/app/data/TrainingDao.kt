package com.aicontrol.app.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface TrainingDao {

    // Training Tasks
    @Query("SELECT * FROM training_tasks ORDER BY is_favorite DESC, last_used DESC")
    fun getAllTasks(): LiveData<List<TrainingTask>>

    @Query("SELECT * FROM training_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TrainingTask?

    @Query("SELECT * FROM training_tasks WHERE category = :category ORDER BY last_used DESC")
    fun getTasksByCategory(category: String): LiveData<List<TrainingTask>>

    @Query("SELECT * FROM training_tasks WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'")
    fun searchTasks(query: String): LiveData<List<TrainingTask>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TrainingTask): Long

    @Update
    suspend fun updateTask(task: TrainingTask)

    @Delete
    suspend fun deleteTask(task: TrainingTask)

    @Query("UPDATE training_tasks SET run_count = run_count + 1, last_used = :timestamp WHERE id = :id")
    suspend fun incrementRunCount(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE training_tasks SET success_count = success_count + 1 WHERE id = :id")
    suspend fun incrementSuccessCount(id: Long)

    @Query("UPDATE training_tasks SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("SELECT DISTINCT category FROM training_tasks")
    suspend fun getCategories(): List<String>

    // Action History
    @Query("SELECT * FROM action_history WHERE task_id = :taskId ORDER BY timestamp DESC LIMIT 100")
    fun getHistoryForTask(taskId: Long): LiveData<List<ActionHistory>>

    @Query("SELECT * FROM action_history ORDER BY timestamp DESC LIMIT 200")
    fun getAllHistory(): LiveData<List<ActionHistory>>

    @Insert
    suspend fun insertHistory(history: ActionHistory)

    @Query("DELETE FROM action_history WHERE timestamp < :before")
    suspend fun deleteOldHistory(before: Long)

    @Query("DELETE FROM action_history WHERE task_id = :taskId")
    suspend fun deleteHistoryForTask(taskId: Long)
}
