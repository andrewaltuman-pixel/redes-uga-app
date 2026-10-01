package dev.pages.redesuga.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Tarea periódica de Android (cada 15 minutos, con conexión) que trae los avisos nuevos. */
public class RevisarAvisos extends Worker {
    public RevisarAvisos(@NonNull Context contexto, @NonNull WorkerParameters parametros) {
        super(contexto, parametros);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Avisos.revisar(getApplicationContext());
        } catch (Exception e) {
            // Sin conexión: la próxima consulta trae lo pendiente
        }
        return Result.success();
    }
}
