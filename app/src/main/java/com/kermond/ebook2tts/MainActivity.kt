package com.kermond.ebook2tts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.kermond.ebook2tts.ui.DiagnoseFragment
import com.kermond.ebook2tts.ui.GuideFragment
import com.kermond.ebook2tts.ui.ModelFragment
import com.kermond.ebook2tts.ui.SampleFragment
import com.kermond.ebook2tts.ui.SettingsFragment
import com.kermond.ebook2tts.ui.VoicesFragment

/**
 * 配套 App 主界面（RQ-3xx）。作者：Kermond。
 */
class MainActivity : AppCompatActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.subtitle = "作者 Kermond · 0.2.0-alpha.1"

        maybeRequestNotifPermission()

        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addTab(tabs.newTab().setText("引导"))
        tabs.addTab(tabs.newTab().setText("模型"))
        tabs.addTab(tabs.newTab().setText("音色库"))
        tabs.addTab(tabs.newTab().setText("示例朗读"))
        tabs.addTab(tabs.newTab().setText("设置"))
        tabs.addTab(tabs.newTab().setText("诊断"))

        if (savedInstanceState == null) {
            show(GuideFragment())
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val f: Fragment = when (tab.position) {
                    0 -> GuideFragment()
                    1 -> ModelFragment()
                    2 -> VoicesFragment()
                    3 -> SampleFragment()
                    4 -> SettingsFragment()
                    else -> DiagnoseFragment()
                }
                show(f)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val route = intent?.getStringExtra("route")
        if (route == "download") {
            tabs.getTabAt(1)?.select()
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commitAllowingStateLoss()
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
