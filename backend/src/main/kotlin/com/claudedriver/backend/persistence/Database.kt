package com.claudedriver.backend.persistence

import com.claudedriver.backend.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/** Owns the connection pool, runs Flyway migrations, and exposes the Exposed [Database] handle. */
class Db private constructor(val dataSource: DataSource, val database: Database) {
    companion object {
        fun connect(config: Config): Db {
            val hikari = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.databaseUrl
                    username = config.databaseUser
                    password = config.databasePassword
                    maximumPoolSize = 8
                    isAutoCommit = false
                    driverClassName = "org.postgresql.Driver"
                },
            )
            Flyway.configure()
                .dataSource(hikari)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            val database = Database.connect(hikari)
            return Db(hikari, database)
        }
    }
}
