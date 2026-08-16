package com.droidnova.allfilereader.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnova.allfilereader.R
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.ui.components.rememberStorageAccessRequest
import com.droidnova.allfilereader.ui.screens.category.FileCategory

private data class HomeCategory(@StringRes val label:Int,val icon:ImageVector,val color:Color,val category:FileCategory?=null,val special:String?=null,val model:DocumentCategory?=null)
@Composable fun HomeScreen(onCategorySelected:(String)->Unit,onDirectoriesSelected:()->Unit,onFavoritesSelected:()->Unit,viewModel:HomeViewModel=hiltViewModel()){
 val state by viewModel.uiState.collectAsStateWithLifecycle();LifecycleResumeEffect(Unit){viewModel.onResume();onPauseOrDispose{}}
 val request=rememberStorageAccessRequest(viewModel::onResume)
 HomeScreenContent(state,viewModel::refresh,request,viewModel::dismissPermission,onCategorySelected,onDirectoriesSelected,onFavoritesSelected)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HomeScreenContent(state:HomeUiState,onRefresh:()->Unit,onAllow:()->Unit,onNotNow:()->Unit,onCategory:(String)->Unit,onDirectories:()->Unit,onFavorites:()->Unit){
 Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),topBar={TopAppBar(title={Text(stringResource(R.string.app_name))})}){pad->
  PullToRefreshBox(state.isRefreshing,onRefresh,Modifier.fillMaxSize().padding(pad)){
   val c=MaterialTheme.colorScheme;val cards=listOf(
    HomeCategory(R.string.category_all,Icons.Default.InsertDriveFile,c.primary,FileCategory.All,model=null),HomeCategory(R.string.pdf,Icons.Default.PictureAsPdf,c.error,FileCategory.Pdf,model=DocumentCategory.Pdf),
    HomeCategory(R.string.word,Icons.Default.Description,c.secondary,FileCategory.Word,model=DocumentCategory.Word),HomeCategory(R.string.excel,Icons.Default.TableChart,c.tertiary,FileCategory.Excel,model=DocumentCategory.Excel),
    HomeCategory(R.string.ppt,Icons.Default.Slideshow,c.error,FileCategory.PowerPoint,model=DocumentCategory.PowerPoint),HomeCategory(R.string.txt,Icons.Default.TextSnippet,c.onSurfaceVariant,FileCategory.Text,model=DocumentCategory.Text),
    HomeCategory(R.string.directories,Icons.Default.Folder,c.tertiary,special="directories"),HomeCategory(R.string.favorites,Icons.Outlined.FavoriteBorder,c.primary,special="favorites"))
   LazyVerticalGrid(GridCells.Fixed(2),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    if(!state.hasAccess&&!state.permissionDismissed)item(span={androidx.compose.foundation.lazy.grid.GridItemSpan(2)}){PermissionCard(onAllow,onNotNow)}
    items(cards,key={it.label}){card->CategoryCard(card,when{card.special=="directories"->stringResource(R.string.browse);card.special=="favorites"->"0";!state.hasAccess->"—";card.category==FileCategory.All->state.documents.size.toString();else->state.count(card.model).toString()}){when(card.special){"directories"->onDirectories();"favorites"->onFavorites();else->onCategory(card.category!!.id)}}}
   }
  }
 }
}
@Composable private fun CategoryCard(card:HomeCategory,count:String,onClick:()->Unit){val label=stringResource(card.label);ElevatedCard(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=88.dp)){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(card.icon,label,tint=card.color,modifier=Modifier.size(32.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(label,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(count,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
@Composable fun PermissionCard(onAllow:()->Unit,onNotNow:()->Unit){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(stringResource(R.string.all_files_access),style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.all_files_access_explanation));Row{Button(onClick=onAllow){Text(stringResource(R.string.allow_access))};TextButton(onClick=onNotNow){Text(stringResource(R.string.not_now))}}}}}

@Preview(showBackground = true, widthDp = 180)
@Composable
private fun CategoryCardPreview() {
    MaterialTheme {
        CategoryCard(
            card = HomeCategory(R.string.pdf, Icons.Default.PictureAsPdf, MaterialTheme.colorScheme.error),
            count = "39",
            onClick = {}
        )
    }
}
