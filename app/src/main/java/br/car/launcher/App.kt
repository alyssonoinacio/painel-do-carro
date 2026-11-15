package br.car.launcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Se o app fechar por erro, guarda o motivo para mostrar na próxima abertura e no Diagnóstico.
        val padrao = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "ultimo_erro.txt").writeText(android.util.Log.getStackTraceString(e).take(4000)) } catch (x: Exception) {}
            padrao?.uncaughtException(t, e)
        }
        criarCanais(this)
        BuscaAtualizacao.agendar(this)
    }

    companion object {
        const val CANAL_ATUALIZACAO = "atualizacoes"

        fun criarCanais(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            (c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CANAL_ATUALIZACAO, "Novas versões", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun ultimoErro(c: Context): String? = File(c.filesDir, "ultimo_erro.txt").takeIf { it.exists() }?.readText()
        fun limparErro(c: Context) { File(c.filesDir, "ultimo_erro.txt").delete() }
    }
}

/** Procura versão nova a cada ~6 h, mesmo sem abrir o app, e avisa por notificação. */
class BuscaAtualizacao : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Updater.verificar(applicationContext, false) { jobFinished(p, false) }
        return true
    }
    override fun onStopJob(p: JobParameters) = true

    companion object {
        fun agendar(c: Context) {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.allPendingJobs.any { it.id == 77 }) return
            js.schedule(JobInfo.Builder(77, ComponentName(c, BuscaAtualizacao::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 3600_000L)
                .setPersisted(true)
                .build())
        }
    }
}
