package com.aitrader.bridge

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.net.Uri
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var capture: Button
    private val strategyIds = mapOf(R.id.strategyEma to "EMA", R.id.strategyRsi to "RSI", R.id.strategyMacd to "MACD", R.id.strategyCandle to "CANDLE", R.id.strategySr to "SR")

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data=result.data
        if(result.resultCode==RESULT_OK && data!=null){
            val i=Intent(this,CaptureService::class.java).apply { putExtra(CaptureService.EXTRA_RESULT_CODE,result.resultCode);putExtra(CaptureService.EXTRA_RESULT_DATA,data) }
            ContextCompat.startForegroundService(this,i); render("Captura iniciada. Abra o gráfico da corretora.")
        } else render("Permissão de captura não concedida.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); prefs=Prefs(this)
        status=findViewById(R.id.textStatus); capture=findViewById(R.id.buttonCapture)
        strategyIds.forEach { (id,key) -> findViewById<CheckBox>(id).apply { isChecked=key in prefs.strategies; setOnCheckedChangeListener { _,_-> saveStrategies() } } }
        findViewById<RadioGroup>(R.id.timeframeGroup).apply {
            check(when(prefs.timeframe){"M5"->R.id.tfM5;"M15"->R.id.tfM15;else->R.id.tfM1})
            setOnCheckedChangeListener { _,id -> prefs.timeframe=when(id){R.id.tfM5->"M5";R.id.tfM15->"M15";else->"M1"}; render("Período ${prefs.timeframe} selecionado.") }
        }
        findViewById<Button>(R.id.buttonOverlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.buttonAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Nas configurações, ative AITraderBridge em Apps instalados / Acessibilidade.")
        }
        findViewById<CheckBox>(R.id.checkAutoTrade).apply {
            isChecked = prefs.autoTradeEnabled
            setOnCheckedChangeListener { _, checked ->
                prefs.autoTradeEnabled = checked
                toast(if (checked) "Automático armado. Só executa se a conta DEMO for identificada." else "Execução automática desligada.")
            }
        }
        capture.setOnClickListener { toggleCapture() }
        findViewById<Button>(R.id.buttonCalibrate).setOnClickListener { calibrate() }
        render("Pronto para analisar qualquer gráfico visível. Não precisa de site nem pareamento.")
    }

    private fun saveStrategies(){ prefs.strategies=strategyIds.filter { (id,_)->findViewById<CheckBox>(id).isChecked }.values.toSet() }
    private fun render(message:String){ status.text=message; capture.text=if(CaptureService.isRunning) "Parar análise" else "Iniciar análise" }
    private fun requestOverlay(){
        if(Settings.canDrawOverlays(this)){toast("Sobreposição liberada.");return}
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
    }
    private fun toggleCapture(){
        if(CaptureService.isRunning){startService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_STOP));render("Análise parada.");return}
        if(!Settings.canDrawOverlays(this)){toast("Libere a sobreposição primeiro.");requestOverlay();return}
        val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    private fun calibrate(){
        val frame=CaptureService.lastFrame
        if(frame==null){toast("Inicie a captura e deixe o gráfico aberto para calibrar.");return}
        val view=layoutInflater.inflate(R.layout.dialog_calibration,null)
        val priceA=view.findViewById<android.widget.EditText>(R.id.priceA); val priceB=view.findViewById<android.widget.EditText>(R.id.priceB)
        val cv=view.findViewById<CalibrationView>(R.id.calibrationView);cv.setFrame(frame)
        prefs.calibration()?.let { priceA.setText(it.priceA.toString());priceB.setText(it.priceB.toString()) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Ajustar escala de preços") .setView(view).setNegativeButton("Cancelar",null).setPositiveButton("Salvar") { _,_->
            val a=priceA.text.toString().replace(',','.').toDoubleOrNull();val b=priceB.text.toString().replace(',','.').toDoubleOrNull()
            if(a==null||b==null||!cv.fractionsReady()){toast("Informe os preços e marque os quatro pontos.");return@setPositiveButton}
            val c=Calibration(a,b,cv.yA,cv.yB,cv.xLeft,cv.xRight)
            if(!c.isValid()){toast("Pontos inválidos. Tente novamente.");return@setPositiveButton}
            prefs.calibrationJson=c.toJson();toast("Escala salva. A leitura local será reiniciada.")
        }.show()
    }
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_LONG).show();render(s)}
    override fun onResume(){
        super.onResume()
        if(::status.isInitialized) {
            val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name.endsWith("TradeAccessibilityService") }
            status.text = if (prefs.autoTradeEnabled && !enabled) "Automático armado, mas falta ativar a acessibilidade do AITraderBridge." else if (CaptureService.isRunning) "Análise local ativa. ${TradeAccessibilityService.lastMessage}" else "Pronto. Selecione estratégias e inicie."
            capture.text = if(CaptureService.isRunning) "Parar análise" else "Iniciar análise"
        }
    }
}
