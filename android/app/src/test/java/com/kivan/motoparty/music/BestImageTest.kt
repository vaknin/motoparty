package com.kivan.motoparty.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Image.ResolutionLevel.UNKNOWN

class BestImageTest {
    private fun img(url: String, w: Int) = Image(url, w, w, UNKNOWN)

    @Test fun smallestAtLeast300() = assertEquals("b",
        Catalog.bestImage(listOf(img("a", 180), img("b", 640), img("c", 1200))))

    @Test fun resizableSongArtIsAskedForBigger() = assertEquals(
        "https://yt3.googleusercontent.com/abc=w544-h544-l90-rj",
        Catalog.bestImage(listOf(
            img("https://yt3.googleusercontent.com/abc=w60-h60-l90-rj", 60),
            img("https://yt3.googleusercontent.com/abc=w120-h120-l90-rj", 120),
        )))

    @Test fun otherSmallArtIsLeftAlone() = assertEquals("https://i.ytimg.com/vi/x/hqdefault.jpg",
        Catalog.bestImage(listOf(img("https://i.ytimg.com/vi/x/hqdefault.jpg", 246))))

    @Test fun none() = assertNull(Catalog.bestImage(emptyList()))
}
