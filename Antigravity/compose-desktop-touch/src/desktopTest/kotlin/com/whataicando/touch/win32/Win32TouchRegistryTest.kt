package com.whataicando.touch.win32

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Win32TouchRegistryTest {

    private val dummyHwnd = HWND(Pointer(12345L))

    @Test
    fun testHitTestNoRegions() {
        Win32TouchRegistry.regions.clear()
        val match = Win32TouchRegistry.hitTest(dummyHwnd, 100, 100)
        assertNull(match)
    }

    @Test
    fun testHitTestSingleRegion() {
        Win32TouchRegistry.regions.clear()
        val regionId = "test-region"
        val rect = Rectangle(50, 50, 100, 100)
        
        Win32TouchRegistry.registerRegion(dummyHwnd, regionId, rect, consumeTouch = true, depth = 5)
        
        // Point outside
        assertNull(Win32TouchRegistry.hitTest(dummyHwnd, 40, 40))
        assertNull(Win32TouchRegistry.hitTest(dummyHwnd, 160, 160))
        
        // Point inside
        val match = Win32TouchRegistry.hitTest(dummyHwnd, 100, 100)
        assertNotNull(match)
        assertEquals(regionId, match.id)
        assertEquals(rect, match.rect)
        assertEquals(true, match.consumeTouch)
        assertEquals(5, match.depth)
    }

    @Test
    fun testHitTestOverlapDepthSorting() {
        Win32TouchRegistry.regions.clear()
        
        val backgroundListId = "background-list"
        val overlayScrimId = "overlay-scrim"
        
        // Background list: bounds covers (0,0 to 200,200), depth = 4
        Win32TouchRegistry.registerRegion(
            dummyHwnd, 
            backgroundListId, 
            Rectangle(0, 0, 200, 200), 
            consumeTouch = true, 
            depth = 4
        )
        
        // Overlay scrim (covers entire screen 0,0 to 500,500), but depth = 2 (lower priority)
        Win32TouchRegistry.registerRegion(
            dummyHwnd, 
            overlayScrimId, 
            Rectangle(0, 0, 500, 500), 
            consumeTouch = false, 
            depth = 2
        )
        
        // At (100, 100), both overlap. The background list should win because depth 4 > depth 2.
        val match1 = Win32TouchRegistry.hitTest(dummyHwnd, 100, 100)
        assertNotNull(match1)
        assertEquals(backgroundListId, match1.id)
        
        // Now, update overlay scrim with priority = 1, giving it depth = 10002
        Win32TouchRegistry.registerRegion(
            dummyHwnd, 
            overlayScrimId, 
            Rectangle(0, 0, 500, 500), 
            consumeTouch = false, 
            depth = 10002 // depth including priority offset (1 * 10000 + 2)
        )
        
        // At (100, 100), overlay scrim should now win because depth 10002 > depth 4!
        val match2 = Win32TouchRegistry.hitTest(dummyHwnd, 100, 100)
        assertNotNull(match2)
        assertEquals(overlayScrimId, match2.id)
        assertEquals(false, match2.consumeTouch) // Should not consume touch, allowing native click promotion
    }

    @Test
    fun testHitTestOverlayHierarchySorting() {
        Win32TouchRegistry.regions.clear()
        
        val backgroundListId = "background-list"
        val overlayScrimId = "overlay-scrim"
        val overlayListId = "overlay-list"
        
        // 1. Background list: depth = 4
        Win32TouchRegistry.registerRegion(
            dummyHwnd,
            backgroundListId,
            Rectangle(0, 0, 200, 200),
            consumeTouch = true,
            depth = 4
        )
        
        // 2. Overlay scrim: priority = 1, depth = 10002
        Win32TouchRegistry.registerRegion(
            dummyHwnd,
            overlayScrimId,
            Rectangle(0, 0, 500, 500),
            consumeTouch = false,
            depth = 10002
        )
        
        // 3. Overlay list: priority = 1, depth = 10005 (nested child inside overlay)
        Win32TouchRegistry.registerRegion(
            dummyHwnd,
            overlayListId,
            Rectangle(50, 50, 150, 150),
            consumeTouch = true,
            depth = 10005
        )
        
        // Hit-test overlay list area (100, 100): overlay list (10005) should win over scrim (10002) and background list (4)
        val match1 = Win32TouchRegistry.hitTest(dummyHwnd, 100, 100)
        assertNotNull(match1)
        assertEquals(overlayListId, match1.id)
        assertEquals(true, match1.consumeTouch) // Custom touch scroll handles it
        
        // Hit-test overlay scrim area outside overlay list (300, 300): overlay scrim (10002) should win over background list (not overlapping background, but shows scrim wins)
        val match2 = Win32TouchRegistry.hitTest(dummyHwnd, 300, 300)
        assertNotNull(match2)
        assertEquals(overlayScrimId, match2.id)
        assertEquals(false, match2.consumeTouch)
    }

    @Test
    fun testHitTestCardScrimWinsWhenRootScrimDisabled() {
        Win32TouchRegistry.regions.clear()
        
        val backgroundListId = "background-list"
        val cardScrimId = "card-scrim"
        
        // 1. Background list: covers 0,0 to 200,200, depth = 4
        Win32TouchRegistry.registerRegion(
            dummyHwnd,
            backgroundListId,
            Rectangle(0, 0, 200, 200),
            consumeTouch = true,
            depth = 4
        )
        
        // 2. Card scrim (covers 50,50 to 150,150), priority = 1, depth = 10003
        Win32TouchRegistry.registerRegion(
            dummyHwnd,
            cardScrimId,
            Rectangle(50, 50, 100, 100),
            consumeTouch = false,
            depth = 10003
        )
        
        // Hit-test outside card, inside background (10, 10): should match background list
        val matchOutside = Win32TouchRegistry.hitTest(dummyHwnd, 10, 10)
        assertNotNull(matchOutside)
        assertEquals(backgroundListId, matchOutside.id)
        
        // Hit-test inside card (100, 100): should match card scrim
        val matchInside = Win32TouchRegistry.hitTest(dummyHwnd, 100, 100)
        assertNotNull(matchInside)
        assertEquals(cardScrimId, matchInside.id)
        assertEquals(false, matchInside.consumeTouch) // Promotes to click
    }

    @Test
    fun testUnregisterRegion() {
        Win32TouchRegistry.regions.clear()
        val regionId = "temporary-region"
        
        Win32TouchRegistry.registerRegion(dummyHwnd, regionId, Rectangle(0, 0, 100, 100), consumeTouch = true, depth = 1)
        assertNotNull(Win32TouchRegistry.hitTest(dummyHwnd, 50, 50))
        
        Win32TouchRegistry.unregisterRegion(dummyHwnd, regionId)
        assertNull(Win32TouchRegistry.hitTest(dummyHwnd, 50, 50))
    }
}
