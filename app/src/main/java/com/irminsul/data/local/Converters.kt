package com.irminsul.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return Gson().toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        if (value == null) return null
        val listType = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, Float>?): String? {
        return Gson().toJson(value)
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, Float>? {
        if (value == null) return null
        val mapType = object : TypeToken<Map<String, Float>>() {}.type
        return Gson().fromJson(value, mapType)
    }
}
