package org.openbot.main; // Asegúrate de que este paquete sea el correcto

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import org.openbot.databinding.ActivityInterpretationBinding; // Esto se generará automáticamente

public class InterpretationActivity extends AppCompatActivity {

    private ActivityInterpretationBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Inflar el layout usando ViewBinding (es la forma moderna y recomendada)
        binding = ActivityInterpretationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Aquí podrías añadir un Toolbar si lo necesitaras para esta actividad específica
        // androidx.appcompat.widget.Toolbar toolbar = binding.toolbar; // Si tu layout incluye un toolbar con id "toolbar"
        // setSupportActionBar(toolbar);
        // getSupportActionBar().setTitle("Interpretación de Imágenes");
    }
}