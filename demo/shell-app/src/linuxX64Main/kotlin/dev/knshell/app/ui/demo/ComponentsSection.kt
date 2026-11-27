package dev.knshell.app.ui.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Align
import dev.knshell.compose.align
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.hExpand
import dev.knshell.compose.width
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.components.element.UBadge
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.element.UCard
import dev.knshell.uikit.components.element.UKbd
import dev.knshell.uikit.components.element.USeparator
import dev.knshell.uikit.components.feedback.UAlert
import dev.knshell.uikit.components.feedback.UProgress
import dev.knshell.uikit.components.feedback.USkeleton
import dev.knshell.uikit.components.feedback.UToast
import dev.knshell.uikit.components.feedback.UToaster
import dev.knshell.uikit.components.form.UCheckbox
import dev.knshell.uikit.components.form.UChecked
import dev.knshell.uikit.components.form.UFormField
import dev.knshell.uikit.components.form.UInput
import dev.knshell.uikit.components.form.USelect
import dev.knshell.uikit.components.form.USlider
import dev.knshell.uikit.components.form.USwitch
import dev.knshell.uikit.components.navigation.UAccordion
import dev.knshell.uikit.components.navigation.UAccordionItem
import dev.knshell.uikit.components.overlays.UDropdownMenu
import dev.knshell.uikit.components.overlays.UMenuItem
import dev.knshell.uikit.components.overlays.UModal
import dev.knshell.uikit.components.overlays.UModalRole
import dev.knshell.uikit.components.overlays.UPopover
import dev.knshell.uikit.components.overlays.UTooltip
import dev.knshell.uikit.core.UAction
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.UTheme

/** A tour of the kit with state of its own, so that every control does something. */
@Composable
internal fun ComponentsSection(toaster: UToaster) {
    val spacing = UTheme.tokens.spacing
    Column(spacing = spacing.lg, modifier = Modifier.hExpand()) {
        Actions(toaster)
        USeparator(label = "Форма")
        Form()
        USeparator(label = "Стан і вміст")
        Status()
    }
}

@Composable
private fun Actions(toaster: UToaster) {
    val spacing = UTheme.tokens.spacing
    var removing by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

    Row(spacing = spacing.sm) {
        UButton(
            "Зберегти",
            onClick = {
                toaster.add(
                    UToast(
                        title = "Збережено",
                        description = "Налаштування застосовано.",
                        color = UColor.Success,
                        actions = listOf(UAction("Скасувати") {}),
                    ),
                )
            },
        )
        UButton("Видалити", onClick = { removing = true }, color = UColor.Error, variant = UVariant.Outline)
        UDropdownMenu(
            items = listOf(
                listOf(
                    UMenuItem("Редагувати", onSelect = {}, icon = Lucide.Pencil, kbds = listOf("Ctrl", "E")),
                    UMenuItem("Копіювати", onSelect = {}, icon = Lucide.Copy),
                ),
                listOf(UMenuItem("Вийти", onSelect = {}, icon = Lucide.LogOut, color = UColor.Error)),
            ),
        ) { trigger ->
            UButton("Меню", onClick = trigger.onClick, modifier = trigger.modifier, color = UColor.Neutral, variant = UVariant.Outline, trailingIcon = Lucide.ChevronDown)
        }
        UPopover(
            open = details,
            onOpenChange = { details = it },
            accessibleLabel = "Подробиці",
            content = {
                Label("Popover живе поза вікном,", align = 0f)
                Label("тому працює і з панелі.", align = 0f)
                UButton("Зрозуміло", onClick = { details = false }, variant = UVariant.Soft)
            },
        ) { trigger ->
            UButton("Подробиці", onClick = trigger.onClick, modifier = trigger.modifier, color = UColor.Neutral, variant = UVariant.Ghost)
        }
        UTooltip("Надіслати сповіщення", kbds = listOf("F8")) { trigger ->
            UButton(
                onClick = {
                    trigger.onClick()
                    toaster.add(UToast(title = "Помилка мережі", description = "Помилки не зникають самі.", color = UColor.Error))
                },
                modifier = trigger.modifier,
                color = UColor.Neutral,
                variant = UVariant.Ghost,
                leadingIcon = Lucide.Bell,
                accessibleLabel = "Приклад помилки",
            )
        }
    }

    UModal(
        open = removing,
        onOpenChange = { removing = it },
        title = "Видалити простір?",
        description = "Вікна переїдуть у перший простір.",
        role = UModalRole.AlertDialog,
        footer = {
            UButton("Скасувати", onClick = { removing = false }, color = UColor.Neutral, variant = UVariant.Outline)
            UButton("Видалити", onClick = { removing = false }, color = UColor.Error)
        },
    )
}

@Composable
private fun Form() {
    val spacing = UTheme.tokens.spacing
    var name by remember { mutableStateOf("workstation") }
    var font by remember { mutableStateOf<String?>("Adwaita Sans") }
    var checked by remember { mutableStateOf(UChecked.Mixed) }
    var wifi by remember { mutableStateOf(true) }
    var volume by remember { mutableStateOf(62f) }

    Column(spacing = spacing.md, modifier = Modifier.width(360)) {
        UFormField("Ім'я хоста", description = "Лише латиниця й дефіс.", error = "Порожнє ім'я".takeIf { name.isEmpty() }) {
            UInput(value = name, onValueChange = { name = it })
        }
        UFormField("Шрифт") {
            USelect(
                items = listOf("Adwaita Sans", "JetBrains Mono", "Noto Sans"),
                value = font,
                onValueChange = { font = it },
                label = { it },
                modifier = Modifier.hExpand(),
            )
        }
        UCheckbox(checked, onCheckedChange = { checked = if (it) UChecked.On else UChecked.Off }, label = "Усі сповіщення")
        USwitch(wifi, onCheckedChange = { wifi = it }, label = "Wi-Fi", description = "Підключатися автоматично")
        USlider(volume, onValueChange = { volume = it }, accessibleLabel = "Гучність", valueText = { "${it.toInt()} %" })
        UProgress(value = volume, max = 100f, accessibleLabel = "Гучність")
    }
}

@Composable
private fun Status() {
    val spacing = UTheme.tokens.spacing
    var open by remember { mutableStateOf(setOf("network")) }

    Column(spacing = spacing.md, modifier = Modifier.hExpand()) {
        Row(spacing = spacing.sm) {
            UBadge("Нове")
            UBadge("Увага", color = UColor.Warning, variant = UVariant.Outline)
            UKbd(listOf("Super", "Space"))
        }
        UAlert(
            title = "Мало місця на диску",
            description = "Лишилося 2 ГБ. Звільніть місце, інакше оновлення не встановиться і система нагадуватиме щодня.",
            color = UColor.Warning,
        )
        UAccordion(
            items = listOf(UAccordionItem("network", "Мережа", icon = Lucide.Wifi), UAccordionItem("sound", "Звук", icon = Lucide.Volume2)),
            expanded = open,
            onExpandedChange = { open = it },
            modifier = Modifier.hExpand(),
        ) { item ->
            Label("Налаштування розділу «${item.label}».", align = 0f)
        }
        UCard(modifier = Modifier.width(260).align(h = Align.Start), accessibleLabel = "Завантаження") {
            Column(spacing = spacing.sm) {
                USkeleton(width = 180)
                USkeleton(width = 120)
            }
        }
    }
}
