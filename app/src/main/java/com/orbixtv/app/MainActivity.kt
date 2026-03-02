package com.orbixtv.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.orbixtv.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navController = findNavController(R.id.nav_host_fragment)

        val sideNav   = binding.root.findViewById<NavigationRailView?>(R.id.side_nav)
        val bottomNav = binding.root.findViewById<BottomNavigationView?>(R.id.bottom_nav)

        when {
            sideNav != null && sideNav.visibility != View.GONE ->
                sideNav.setupWithNavController(navController)
            bottomNav != null && bottomNav.visibility != View.GONE ->
                bottomNav.setupWithNavController(navController)
        }
    }
}
