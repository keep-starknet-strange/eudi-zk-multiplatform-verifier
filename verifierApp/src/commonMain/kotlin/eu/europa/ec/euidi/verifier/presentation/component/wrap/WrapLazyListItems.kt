/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.euidi.verifier.presentation.component.wrap

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CardColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.europa.ec.euidi.verifier.presentation.component.ClickableArea
import eu.europa.ec.euidi.verifier.presentation.component.ListItemDataUi
import eu.europa.ec.euidi.verifier.presentation.component.utils.SPACING_MEDIUM

/**
 * Lazy counterpart of [WrapListItems]: renders the same grouped card with dividers, but composes
 * its rows on demand through a [LazyColumn] instead of eagerly laying out every item. Prefer this
 * over [WrapListItems] for long lists (e.g. country selection) where rendering everything up front
 * causes a noticeable load delay.
 *
 * The [LazyColumn] is the scroll container, so this composable must NOT be placed inside a
 * `verticalScroll` parent. Give it a bounded height via [modifier] (e.g. `weight(1f)` within a
 * Column, or `fillMaxSize`).
 */
@Composable
fun WrapLazyListItems(
    modifier: Modifier = Modifier,
    items: List<ListItemDataUi>,
    onItemClick: ((item: ListItemDataUi) -> Unit)?,
    hideSensitiveContent: Boolean = false,
    mainContentVerticalPadding: Dp? = null,
    clickableAreas: List<ClickableArea>? = null,
    throttleClicks: Boolean = true,
    addDivider: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    overlineTextStyle: TextStyle? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    WrapCard(
        modifier = modifier,
        shape = shape,
        colors = colors,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.itemId },
            ) { index, item ->
                WrapListItem(
                    modifier = Modifier.fillMaxWidth(),
                    item = item,
                    onItemClick = onItemClick,
                    throttleClicks = throttleClicks,
                    hideSensitiveContent = hideSensitiveContent,
                    mainContentVerticalPadding = mainContentVerticalPadding,
                    clickableAreas = clickableAreas,
                    overlineTextStyle = overlineTextStyle,
                    shape = RectangleShape,
                )

                if (addDivider && index < items.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = SPACING_MEDIUM.dp))
                }
            }
        }
    }
}
