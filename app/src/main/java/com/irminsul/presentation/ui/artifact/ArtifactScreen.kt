package com.irminsul.presentation.ui.artifact

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.irminsul.presentation.state.ArtifactUiState
import com.irminsul.presentation.viewmodel.ArtifactViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactScreen(
    viewModel: ArtifactViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("圣遗物数据") },
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
            } else if (uiState.artifacts.isEmpty()) {
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
                    items(uiState.artifacts) { artifact ->
                        ArtifactCard(artifact = artifact)
                    }
                }
            }
        }
    }
}

@Composable
fun ArtifactCard(artifact: com.irminsul.data.local.entity.ArtifactEntity) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = artifact.setName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "稀有度: ${"★".repeat(artifact.rarity)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "等级: ${artifact.level}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "主词条: ${artifact.mainStatKey} ${artifact.mainStatValue}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
