package com.example.sistemaantirrobo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Login con Firebase Auth + estado compartido en Realtime Database (nodo "sistema").
class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var refSistema: DatabaseReference
    private var escucha: ValueEventListener? = null

    // Notificaciones de alerta
    private var ultimaHoraNotificada: Long = -1   // -1 = aún no se lee el primer dato
    private val pedirPermisoNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var screenLogin: View
    private lateinit var screenEstado: View
    private lateinit var screenControl: View
    private lateinit var screenPerfil: View
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()

        crearCanalNotificaciones()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refSistema = FirebaseDatabase.getInstance().getReference("sistema")

        screenLogin = findViewById(R.id.screenLogin)
        screenEstado = findViewById(R.id.screenEstado)
        screenControl = findViewById(R.id.screenControl)
        screenPerfil = findViewById(R.id.screenPerfil)
        bottomNav = findViewById(R.id.bottomNav)

        val etCorreo = findViewById<EditText>(R.id.etCorreo)
        val etClave = findViewById<EditText>(R.id.etClave)
        val tvError = findViewById<TextView>(R.id.tvError)
        val btnIngresar = findViewById<Button>(R.id.btnIngresar)
        val tvCrearCuenta = findViewById<TextView>(R.id.tvCrearCuenta)
        val swArmado = findViewById<SwitchCompat>(R.id.swArmado)
        val tvSistema = findViewById<TextView>(R.id.tvSistema)
        val btnSilenciar = findViewById<Button>(R.id.btnSilenciar)
        val btnSimular = findViewById<Button>(R.id.btnSimular)
        val btnCerrar = findViewById<Button>(R.id.btnCerrarSesion)
        val tvPerfilCorreo = findViewById<TextView>(R.id.tvPerfilCorreo)
        val tvRol = findViewById<TextView>(R.id.tvRol)
        val cardAlerta = findViewById<LinearLayout>(R.id.cardAlerta)
        val ivAlerta = findViewById<ImageView>(R.id.ivAlerta)
        val tvAlerta = findViewById<TextView>(R.id.tvAlerta)
        val tvHora = findViewById<TextView>(R.id.tvHora)

        // Evita que el cambio hecho por la base de datos vuelva a escribirse en ella
        var actualizando = false

        fun pintar(armado: Boolean, alerta: Boolean, hora: String) {
            actualizando = true
            swArmado.isChecked = armado
            actualizando = false

            tvSistema.text = if (armado) "Armado" else "Desarmado"
            tvSistema.setTextColor(Color.parseColor(if (armado) "#2E7D32" else "#B3261E"))

            if (alerta) {
                cardAlerta.setBackgroundColor(Color.parseColor("#FDECEA"))
                tvAlerta.text = "Vibración detectada"
                tvAlerta.setTextColor(Color.parseColor("#B3261E"))
                tvHora.text = hora
                tvHora.visibility = View.VISIBLE
                ivAlerta.setImageResource(android.R.drawable.ic_dialog_alert)
            } else {
                cardAlerta.setBackgroundColor(Color.parseColor("#E8F5E9"))
                tvAlerta.text = "Todo en orden"
                tvAlerta.setTextColor(Color.parseColor("#2E7D32"))
                tvHora.visibility = View.GONE
                ivAlerta.setImageResource(android.R.drawable.ic_lock_idle_lock)
            }
        }

        fun escucharSistema() {
            if (escucha != null) return
            escucha = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val armado = s.child("armado").getValue(Boolean::class.java) ?: true
                    val alerta = s.child("alerta").getValue(Boolean::class.java) ?: false
                    // "hora" llega como marca de tiempo (ms) desde el Arduino o la app
                    val horaCruda = s.child("hora").value
                    val hora = when (horaCruda) {
                        is Number -> SimpleDateFormat("dd/MM, HH:mm:ss", Locale.getDefault())
                            .format(Date(horaCruda.toLong()))
                        null -> ""
                        else -> horaCruda.toString()
                    }
                    // Notificación cuando llega una alerta nueva (no al abrir la app)
                    val horaNum = (horaCruda as? Number)?.toLong() ?: 0L
                    if (ultimaHoraNotificada == -1L) {
                        ultimaHoraNotificada = horaNum
                    } else if (alerta && horaNum != ultimaHoraNotificada) {
                        ultimaHoraNotificada = horaNum
                        mostrarNotificacion(hora)
                    }

                    pintar(armado, alerta, hora)
                }

                override fun onCancelled(error: DatabaseError) {
                    tvAlerta.text = "Sin acceso a los datos"
                }
            }
            refSistema.addValueEventListener(escucha!!)
        }

        fun dejarDeEscuchar() {
            escucha?.let { refSistema.removeEventListener(it) }
            escucha = null
            ultimaHoraNotificada = -1
        }

        fun mostrarError(mensaje: String) {
            tvError.text = mensaje
            tvError.visibility = View.VISIBLE
        }

        fun validar(correo: String, clave: String): Boolean {
            return when {
                !Patterns.EMAIL_ADDRESS.matcher(correo).matches() -> {
                    mostrarError("Ingresa un correo válido, por ejemplo nombre@correo.cl.")
                    false
                }
                clave.length < 6 -> {
                    mostrarError("La contraseña debe tener al menos 6 caracteres.")
                    false
                }
                else -> {
                    tvError.visibility = View.GONE
                    true
                }
            }
        }

        // Aplica el rol: solo el administrador ve la pestaña Control
        fun aplicarRol(rol: String) {
            val esAdmin = rol == "administrador"
            tvRol.text = if (esAdmin) "Administrador" else "Monitor"
            bottomNav.menu.findItem(R.id.nav_control)?.isVisible = esAdmin
        }

        // Lee el rol del usuario; si no existe, lo crea como monitor
        fun cargarRol(uid: String, correo: String) {
            val refUsuario = FirebaseDatabase.getInstance().getReference("usuarios").child(uid)
            refUsuario.get()
                .addOnSuccessListener { snap ->
                    val rol = snap.child("rol").getValue(String::class.java)
                    if (rol == null) {
                        refUsuario.setValue(mapOf("correo" to correo, "rol" to "monitor"))
                        aplicarRol("monitor")
                    } else {
                        aplicarRol(rol)
                    }
                }
                .addOnFailureListener { aplicarRol("monitor") } // ante la duda, sin permisos
        }

        fun entrar(correo: String) {
            tvPerfilCorreo.text = correo
            aplicarRol("monitor") // por defecto, hasta leer el rol real
            auth.currentUser?.uid?.let { cargarRol(it, correo) }
            bottomNav.visibility = View.VISIBLE
            bottomNav.selectedItemId = R.id.nav_estado
            mostrar(screenEstado)
            escucharSistema()
        }

        btnIngresar.setOnClickListener {
            val correo = etCorreo.text.toString().trim()
            val clave = etClave.text.toString()
            if (!validar(correo, clave)) return@setOnClickListener

            btnIngresar.isEnabled = false
            auth.signInWithEmailAndPassword(correo, clave)
                .addOnCompleteListener { tarea ->
                    btnIngresar.isEnabled = true
                    if (tarea.isSuccessful) {
                        entrar(auth.currentUser?.email ?: correo)
                    } else {
                        mostrarError("Correo o contraseña incorrectos.")
                    }
                }
        }

        tvCrearCuenta.setOnClickListener {
            val correo = etCorreo.text.toString().trim()
            val clave = etClave.text.toString()
            if (!validar(correo, clave)) return@setOnClickListener

            tvCrearCuenta.isEnabled = false
            auth.createUserWithEmailAndPassword(correo, clave)
                .addOnCompleteListener { tarea ->
                    tvCrearCuenta.isEnabled = true
                    if (tarea.isSuccessful) {
                        entrar(auth.currentUser?.email ?: correo)
                    } else {
                        mostrarError("No se pudo crear la cuenta. Revisa los datos o prueba con otro correo.")
                    }
                }
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_estado -> mostrar(screenEstado)
                R.id.nav_control -> mostrar(screenControl)
                R.id.nav_perfil -> mostrar(screenPerfil)
            }
            true
        }

        // Control: armar o desarmar -> se guarda en la base de datos
        swArmado.setOnCheckedChangeListener { _, armado ->
            if (!actualizando) refSistema.child("armado").setValue(armado)
        }

        // Control: silenciar la alarma -> se guarda en la base de datos
        btnSilenciar.setOnClickListener {
            refSistema.child("alerta").setValue(false)
        }

        // Prueba: simula que el sensor detectó vibración (luego lo hará el Arduino/ESP-01)
        btnSimular.setOnClickListener {
            val datos = mapOf("hora" to ServerValue.TIMESTAMP, "alerta" to true)
            refSistema.updateChildren(datos)
                .addOnSuccessListener {
                    Toast.makeText(this, "Alerta enviada", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        btnCerrar.setOnClickListener {
            dejarDeEscuchar()
            auth.signOut()
            bottomNav.visibility = View.GONE
            etClave.text.clear()
            mostrar(screenLogin)
        }

        // Si ya había una sesión iniciada, entra directo
        auth.currentUser?.email?.let { entrar(it) }
    }

    override fun onDestroy() {
        escucha?.let { refSistema.removeEventListener(it) }
        escucha = null
        super.onDestroy()
    }

    private fun crearCanalNotificaciones() {
        val canal = NotificationChannel(
            "alertas", "Alertas de vibración", NotificationManager.IMPORTANCE_HIGH
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun mostrarNotificacion(hora: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendiente = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notificacion = NotificationCompat.Builder(this, "alertas")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("¡Vibración detectada!")
            .setContentText("Se detectó movimiento en el sistema antirrobo. $hora")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendiente)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notificacion)
    }

    private fun mostrar(pantalla: View) {
        screenLogin.visibility = View.GONE
        screenEstado.visibility = View.GONE
        screenControl.visibility = View.GONE
        screenPerfil.visibility = View.GONE
        pantalla.visibility = View.VISIBLE
    }
}