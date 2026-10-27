package com.ytstyle.bili.ui.vip

import com.ytstyle.bili.ui.theme.AppTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.account.AccountViewModel
import com.ytstyle.bili.ui.theme.VipPink
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Membership centre.
 *
 * This is the one commercial surface kept from the domestic client. Everything
 * else — 会员购 merch store, 课堂 courses, 漫画, 游戏中心, 直播推广 — is absent.
 * Benefit copy is descriptive of what a real membership grants; no purchase
 * flow is implemented, so there is nothing here that can take payment.
 */
@Composable
fun VipScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppTheme.colors.onBackground,
                )
            }
            Text(
                text = "大会员中心",
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onBackground,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            VipStatusCard(
                isVip = state.isVip,
                label = state.vipLabel,
                uname = state.uname,
                isLoggedIn = state.isLoggedIn,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "会员权益",
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onBackground,
            )
            Spacer(Modifier.height(12.dp))

            BenefitRow(
                icon = Icons.Filled.Hd,
                title = "1080P 高码率",
                subtitle = "解锁更高清晰度与帧率",
            )
            BenefitRow(
                icon = Icons.Filled.MovieFilter,
                title = "会员专享内容",
                subtitle = "抢先看与专享番剧",
            )
            BenefitRow(
                icon = Icons.Filled.CardGiftcard,
                title = "B 币礼包",
                subtitle = "每月领取会员购优惠券",
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "本应用不提供购买、续费或支付功能，会员状态以账号实际权益为准。",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VipStatusCard(
    isVip: Boolean,
    label: String,
    uname: String,
    isLoggedIn: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surfaceElevated)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = VipPink,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = when {
                        !isLoggedIn -> "未登录"
                        isVip -> "大会员"
                        else -> "普通用户"
                    },
                    color = AppTheme.colors.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    text = if (label.isNotEmpty()) label else uname.ifEmpty { "登录后查看会员状态" },
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = VipPink,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title, color = AppTheme.colors.onBackground, fontSize = 14.sp)
            Text(text = subtitle, color = AppTheme.colors.onBackgroundSecondary, fontSize = 12.sp)
        }
    }
}
