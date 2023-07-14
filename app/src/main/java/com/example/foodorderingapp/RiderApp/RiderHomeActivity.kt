package com.example.foodorderingapp.RiderApp

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.foodorderingapp.Response.CustomResponse
import com.example.foodorderingapp.RiderApp.Services.RiderLocationService
import com.example.foodorderingapp.RiderApp.ViewModels.RiderHomeViewModel
import com.example.foodorderingapp.Utils.Constants
import com.example.foodorderingapp.Utils.Constants.ORDER_IN_DELIVERY
import com.example.foodorderingapp.Utils.Constants.RIDER_ORDER_ID
import com.example.foodorderingapp.databinding.ActivityRiderHomeBinding
import com.example.foodorderingapp.models.DeliveryInfo
import com.example.foodorderingapp.models.DriverInfo
import com.example.foodorderingapp.models.Order
import com.example.foodorderingapp.models.OrderTracking
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RiderHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRiderHomeBinding
    private lateinit var riderViewModel: RiderHomeViewModel
    private val locationPermission:MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var orderPickedTracking: MutableLiveData<OrderTracking?> = MutableLiveData()

    @Inject
    lateinit var auth: FirebaseAuth
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRiderHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)


        riderViewModel = ViewModelProvider(this).get(RiderHomeViewModel::class.java)

        val orderId = riderViewModel.checkRunningOrder()
        orderId?.let {

            startActivity(
                Intent(this, RiderMapActivity::class.java).apply {
                    putExtra(RIDER_ORDER_ID,orderId)
                }
            )
            finish()
            return
        }
        showOrders()
    }

    fun showOrders() {
        val adapter = RiderOrderAdapter(listener = object : RiderOrderConfirmClickListener {
            override fun onConfirm(order: Order) {
                locationPermission.value = checkPermission()
                if(checkPermission()){
                    locationPermission.value = true
                }
                else{
                    getPermissions()
                }
                CoroutineScope(Dispatchers.Main).launch {
                    locationPermission.collectLatest {
                        if(it){
                            getCurrentLocation()
                            orderPickedTracking.observe(this@RiderHomeActivity) {
                                it?.let {
                                    riderViewModel.updateOrderStatus(
                                        order.orderId,
                                        it
                                    ) { success, exception ->
                                        if (success) {
                                            riderViewModel.setRunningOrder(order.orderId)
                                            riderViewModel.setCustomerLatLng(order.customerDeliveryInfo)
                                            startActivity(
                                                Intent(
                                                    this@RiderHomeActivity,
                                                    RiderMapActivity::class.java
                                                )
                                            )
                                            finish()
                                        } else {
                                            exception?.let {
                                                Log.e(
                                                    Constants.MY_TAG,
                                                    exception?.message.toString()
                                                )
                                                return@updateOrderStatus
                                            }
                                            Log.e(Constants.MY_TAG, "Order Does not exist")

                                        }

                                    }

                                    val serviceIntent = Intent(
                                        this@RiderHomeActivity,
                                        RiderLocationService::class.java
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(serviceIntent)
                                    } else {
                                        startService(serviceIntent)
                                    }
                                }
                                if(it == null){
                                    val alertDialog = AlertDialog.Builder(this@RiderHomeActivity)
                                    alertDialog.setTitle("Information")
                                    alertDialog.setMessage("Enable Location")
                                    alertDialog.show()
                                }
                            }

                        }
                        else{
                            getPermissions()
                        }
                    }
                }


            }
        })
        binding.rlNewOrders.layoutManager = LinearLayoutManager(this)
        binding.rlNewOrders.adapter = adapter

        riderViewModel.newOrdersList.observe(this) {
            when (it) {
                is CustomResponse.Loading -> {
                    binding.rlNewOrders.visibility = View.INVISIBLE
                    binding.progressBarRiderOrders.visibility = View.VISIBLE

                }
                is CustomResponse.Success -> {
                    if (it.data != null) {
                        binding.rlNewOrders.visibility = View.VISIBLE
                        binding.progressBarRiderOrders.visibility = View.GONE
                        adapter.setList(it.data)
                    }
                }
                is CustomResponse.Error -> {
                    val alertDialog = AlertDialog.Builder(this)
                    alertDialog.setTitle("Error Message")
                    alertDialog.setMessage(it.errorMessage)
                    alertDialog.show()
                }
                else -> {}
            }
        }

    }

    private fun setOrderTracking(latLng: LatLng) {
        val driverInfo = auth.currentUser?.let {
            DriverInfo(
                driverId = auth.currentUser!!.uid,
                driverName = "Daniyal",
                driverMobileNumber = "03234262432",
            )
        }

        var deliveryInfo = DeliveryInfo(latLng.latitude,latLng.longitude)

        orderPickedTracking.postValue(OrderTracking(
            status = ORDER_IN_DELIVERY,
            driverInfo = driverInfo,
            deliveryInfo = deliveryInfo
        ) )
    }

    fun getCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            getPermissions()
        } else {
            val fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

            fusedLocationProviderClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        setOrderTracking(LatLng(location.latitude, location.longitude))

                    }
                    else{
                        Toast.makeText(this@RiderHomeActivity,"Please Enable Location",Toast.LENGTH_SHORT).show()
                        orderPickedTracking.postValue(null)

                    }
                }


        }

    }

    fun checkPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ){
            return true
        }
        return false
    }
    fun getPermissions() {
        requestMultiplePermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val isGranted = permissions.all { it.value }
            if (isGranted) {
                locationPermission.value = true
            } else {
                locationPermission.value = false
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }

        }
}