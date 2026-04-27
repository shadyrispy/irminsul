package com.irminsul.presentation.ui.weapon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.irminsul.presentation.state.WeaponUiState
import com.irminsul.presentation.viewmodel.WeaponViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeaponScreen(
    viewModel: WeaponViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("武器数据") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.weapons.isEmpty()) {
                Text(
                    text = "暂无数据",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.weapons) { weapon ->
                        WeaponCard(weapon = weapon)
                    }
                }
            }
        }
    }
}

@Composable
fun WeaponCard(weapon: com.irminsul.data.local.entity.WeaponEntity) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = weapon.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "等级: ${weapon.level}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "稀有度: ${"★".repeat(weapon.rarity)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "精炼: ${weapon.refinement}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
