package fr.smarquis.ar_toolbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaPlayer.OnPreparedListener
import android.net.Uri
import android.text.Layout
import android.text.style.AlignmentSpan
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import com.google.ar.core.*
import com.google.ar.core.Anchor.CloudAnchorState.*
import com.google.ar.core.AugmentedImage.TrackingMethod.FULL_TRACKING
import com.google.ar.core.TrackingState.*
import com.google.ar.sceneform.*
import com.google.ar.sceneform.Camera
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.assets.RenderableSource
import com.google.ar.sceneform.assets.RenderableSource.SourceType.GLB
import com.google.ar.sceneform.assets.RenderableSource.SourceType.GLTF2
import com.google.ar.sceneform.collision.RayHit
import com.google.ar.sceneform.collision.Sphere
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.*
import com.google.ar.sceneform.rendering.MaterialFactory.makeOpaqueWithColor
import com.google.ar.sceneform.ux.BaseTransformableNode
import com.google.ar.sceneform.ux.TransformableNode
import com.google.ar.sceneform.animation.ModelAnimator
import net.named_data.jndn.*
import net.named_data.jndn.security.KeyChain
import net.named_data.jndn.security.SafeBag
import net.named_data.jndn.util.Blob
import net.named_data.jndn.util.SegmentFetcher
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass
import kotlin.text.Typography.leftGuillemete
import kotlin.text.Typography.rightGuillemete

sealed class Nodes(
    name: String,
    coordinator: Coordinator,
    private val settings: Settings
) : TransformableNode(coordinator) {

    interface FacingCamera

    companion object {

        private const val PLANE_ANCHORING_DISTANCE = 2F
        private const val DEFAULT_POSE_DISTANCE = 2F

        private val IDS: MutableMap<KClass<*>, AtomicLong> = mutableMapOf()

        fun Any.newId(): Long =
            IDS.getOrElse(this::class, { AtomicLong().also { IDS[this::class] = it } })
                .incrementAndGet()

        fun defaultPose(ar: ArSceneView): Pose {
            // Place the object at default position and return it for creating the anchor
            // Get the center of the display
            val centerX = ar.width / 2F
            val centerY = ar.height / 2F
            val hits = ar.arFrame?.hitTest(centerX, centerY)
            // Get the hit position on the plane
            val planeHitPose = hits?.firstOrNull {
                (it.trackable as? Plane)?.isPoseInPolygon(it.hitPose) == true && it.distance <= PLANE_ANCHORING_DISTANCE
            }?.hitPose
            if (planeHitPose != null) return planeHitPose
            val ray = ar.scene.camera.screenPointToRay(centerX, centerY)
            val point = ray.getPoint(DEFAULT_POSE_DISTANCE)
            return Pose.makeTranslation(point.x, point.y, point.z)
        }
    }

    init {
        this.name = "$name #${newId()}"
        scaleController.apply {
            minScale = 0.25F
            maxScale = 5F
        }
        @Suppress("LeakingThis")
        if (this is FacingCamera) rotationController.isEnabled = false
    }

    var onNodeUpdate: ((Nodes) -> Any)? = null

    internal fun anchor(): Anchor? = (parent as? AnchorNode)?.anchor

    override fun getTransformationSystem(): Coordinator =
        super.getTransformationSystem() as Coordinator

    override fun setRenderable(renderable: Renderable?) {
        super.setRenderable(renderable?.apply {
            isShadowCaster = settings.shadows.get()
            isShadowReceiver = settings.shadows.get()
        })
    }

    override fun onUpdate(frameTime: FrameTime) {
        onNodeUpdate?.invoke(this)
        if (this is FacingCamera) {
            facingCamera()
        }
    }

    private fun facingCamera() {
        // Buggy when dragging because TranslationController already handles it's own rotation on each update.
        if (isTransforming) return /*Prevent infinite loop*/
        val camera = scene?.camera ?: return
        val direction = Vector3.subtract(camera.worldPosition, worldPosition)
        worldRotation = Quaternion.lookRotation(direction, Vector3.up())
    }

    open fun attach(anchor: Anchor, scene: Scene, focus: Boolean = false) {
        // Set the parent of the anchor to be the scene
        setParent(AnchorNode(anchor).apply { setParent(scene) })
        if (focus) {
            transformationSystem.focusNode(this)
        }
    }

    open fun detach() {
        if (this == transformationSystem.selectedNode) {
            transformationSystem.selectNode(selectionContinuation())
        }
        (parent as? AnchorNode)?.anchor?.detach()
        setParent(null)
    }

    open fun selectionContinuation(): BaseTransformableNode? = null

    open fun statusIcon(): Int =
        if (isActive && isEnabled && (parent as? AnchorNode)?.isTracking == true)
            android.R.drawable.presence_online
        else
            android.R.drawable.presence_invisible

    override fun onTap(hitTestResult: HitTestResult?, motionEvent: MotionEvent?) {
        super.onTap(hitTestResult, motionEvent)
        if (isTransforming) return // ignored when dragging over a small distance
        transformationSystem.focusNode(this)
    }
}

sealed class MaterialNode(
    name: String,
    val properties: MaterialProperties,
    coordinator: Coordinator,
    settings: Settings
) : Nodes(name, coordinator, settings) {

    init {
        update()
    }

    fun update(block: (MaterialProperties.() -> Unit) = {}) {
        properties.update(renderable?.material, block)
    }

}

class Sphere(
    context: Context,
    properties: MaterialProperties,
    coordinator: Coordinator,
    settings: Settings
) : MaterialNode("Sphere", properties, coordinator, settings) {

    companion object {
        private const val RADIUS = 0.05F
        private val CENTER = Vector3(0F, RADIUS, 0F)
    }

    init {
        val color = properties.color.toArColor()
        makeOpaqueWithColor(context.applicationContext, color)
            .thenAccept { renderable = ShapeFactory.makeSphere(RADIUS, CENTER, it) }
    }

}

class Cylinder(
    context: Context,
    properties: MaterialProperties,
    coordinator: Coordinator,
    settings: Settings
) : MaterialNode("Cylinder", properties, coordinator, settings) {

    companion object {
        const val RADIUS = 0.05F
        const val HEIGHT = RADIUS * 2
        val CENTER = Vector3(0F, HEIGHT / 2, 0F)
    }

    init {
        val color = properties.color.toArColor()
        makeOpaqueWithColor(context.applicationContext, color)
            .thenAccept { renderable = ShapeFactory.makeCylinder(RADIUS, HEIGHT, CENTER, it) }
    }

}

class Cube(
    context: Context,
    properties: MaterialProperties,
    coordinator: Coordinator,
    settings: Settings
) : MaterialNode("Cube", properties, coordinator, settings) {

    companion object {
        private const val SIZE = 0.1F
        private val CENTER = Vector3(0F, SIZE / 2, 0F)
    }

    init {
        val color = properties.color.toArColor()
        makeOpaqueWithColor(context.applicationContext, color)
            .thenAccept {
                renderable = ShapeFactory.makeCube(Vector3.one().scaled(SIZE), CENTER, it)
            }
    }

}

class Measure(
    private val context: Context,
    properties: MaterialProperties,
    coordinator: Coordinator,
    settings: Settings
) : MaterialNode("Measure", properties, coordinator, settings) {

    companion object {
        private const val SPHERE_RADIUS = 0.01f
        private const val SPHERE_COLLISION_RADIUS = SPHERE_RADIUS * 5
        private const val CYLINDER_RADIUS = SPHERE_RADIUS * 0.5F
    }

    private var previous: Measure? = null
    private var next: Measure? = null
    private var join: Join? = null

    init {
        rotationController.isEnabled = false
        scaleController.isEnabled = false
        makeOpaqueWithColor(context.applicationContext, properties.color.toArColor()).thenAccept {
            renderable = ShapeFactory.makeSphere(SPHERE_RADIUS, Vector3.zero(), it)
                .apply { collisionShape = Sphere(SPHERE_COLLISION_RADIUS, Vector3.zero()) }
            join?.applyMaterial(it)
        }
        linkTo(lastSelected())
    }

    private fun linkTo(to: Measure?) {
        if (to == null) return
        join?.let { removeChild(it) }
        previous = to.apply { next = this@Measure }
        join = Join(to).apply {
            this@Measure.addChild(this)
            this@Measure.renderable?.material?.let { applyMaterial(it) }
        }
    }

    private fun unlink() {
        previous?.next = null
        next?.run {
            previous = null
            removeChild(join)
        }
    }

    private fun last(): Measure = next?.last() ?: this

    private fun lastSelected(): Measure? = (transformationSystem.selectedNode as? Measure)?.last()

    override fun selectionContinuation(): BaseTransformableNode? = previous ?: next

    override fun setParent(parent: NodeParent?) {
        super.setParent(parent)
        if (parent == null) unlink()
    }

    override fun attach(anchor: Anchor, scene: Scene, focus: Boolean) {
        super.attach(anchor, scene, focus)
        transformationSystem.selectNode(this)
    }

    override fun detach(): Unit = when (val last = last()) {
        /* detach() self and propagate to the previous */
        this -> super.detach().also { previous?.detach() }
        /* Run detach() on the last */
        else -> last.detach()
    }

    fun undo(): Unit = super.detach().also { next?.linkTo(previous) }

    fun formatMeasure(): String = when {
        previous == null && next == null -> "…"
        previous == null && next?.next == null -> context.getString(
            R.string.format_measure_single,
            "",
            formatNextDistance()
        )
        next == null && previous?.previous == null -> context.getString(
            R.string.format_measure_single,
            formatPreviousDistance(),
            ""
        )
        else -> context.getString(
            R.string.format_measure_multiple,
            formatPreviousDistance(),
            formatNextDistance(),
            formatDistance(context, totalMeasurePrevious() + totalMeasureNext())
        )
    }

    private fun totalMeasurePrevious(): Double =
        previous?.let { it.totalMeasurePrevious() + distance(this, it) } ?: .0

    private fun totalMeasureNext(): Double =
        next?.let { distance(this, it) + it.totalMeasureNext() } ?: .0

    private fun formatPreviousDistance(): String = previous?.let {
        it.formatPreviousDistance() + formatDistance(
            context,
            this,
            it
        ) + " $leftGuillemete "
    }.orEmpty()

    private fun formatNextDistance(): String = next?.let {
        " $rightGuillemete " + formatDistance(
            context,
            this,
            it
        ) + it.formatNextDistance()
    }.orEmpty()

    private inner class Join(private val previous: Node) : Node() {

        private val scale = Vector3.one()

        init {
            setOnTapListener { _, _ ->
                Toast.makeText(context, formatDistance(context, this, previous), Toast.LENGTH_SHORT)
                    .apply { setGravity(Gravity.CENTER, 0, 0) }.show()
            }
        }

        override fun onUpdate(frameTime: FrameTime) {
            super.onUpdate(frameTime)
            val start: Vector3 = this@Measure.worldPosition
            val end: Vector3 = previous.worldPosition
            localScale = scale.apply {
                y = distance(start, end).toFloat()
            }
            worldPosition = Vector3.lerp(start, end, 0.5f)
            val direction = Vector3.subtract(start, end).normalized()
            val quaternion = Quaternion.lookRotation(direction, Vector3.up())
            worldRotation =
                Quaternion.multiply(quaternion, Quaternion.axisAngle(Vector3.right(), 90f))
        }

        fun applyMaterial(material: Material?) {
            renderable = ShapeFactory.makeCylinder(CYLINDER_RADIUS, 1F, Vector3.zero(), material)
        }

    }

}

class Layout(
    context: Context,
    coordinator: Coordinator,
    settings: Settings
) : Nodes("Layout", coordinator, settings), Footprint.Invisible, Nodes.FacingCamera {

    companion object {
        private const val HEIGHT = 0.3F
    }

    init {
        ViewRenderable.builder()
            .setView(
                ContextThemeWrapper(
                    context.applicationContext,
                    R.style.Theme_MaterialComponents
                ), R.layout.view_renderable_layout
            )
            .setSizer(FixedHeightViewSizer(HEIGHT)).build()
            .thenAccept { renderable = it }
    }

    override fun setRenderable(renderable: Renderable?) {
        super.setRenderable(renderable)
        renderable?.apply {
            isShadowCaster = false
            isShadowReceiver = false
        }
    }

}

class Andy(
    context: Context,
    coordinator: Coordinator,
    settings: Settings
) : Nodes("Andy", coordinator, settings) {

    companion object{
        var andyRenderable: ModelRenderable? = null
        private var nextAnimation = 0
        private var animator: ModelAnimator? = null
        private var hatRenderable: ModelRenderable? = null
        private var face: Face? = null

        /* Apply the animation to cap */
        private var hatNode: Node? = null
        private var andy: SkeletonNode? = null
        private const val HAT_BONE_NAME = "hat_point"


        /* Loading the animation command from NDN network */
        private const val TAG = "NDN-Animation-Client"
        private var producerName = "/edge"
        private var lastAnimationName: String? = null
        private var currentAnimation: String? = null
        private val DEFAULT_RSA_PUBLIC_KEY_DER = toBuffer(
            intArrayOf(
                0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00, 0x30, 0x82, 0x01, 0x0a, 0x02, 0x82, 0x01, 0x01,
                0x00, 0xb8, 0x09, 0xa7, 0x59, 0x82, 0x84, 0xec, 0x4f, 0x06, 0xfa, 0x1c, 0xb2, 0xe1, 0x38, 0x93,
                0x53, 0xbb, 0x7d, 0xd4, 0xac, 0x88, 0x1a, 0xf8, 0x25, 0x11, 0xe4, 0xfa, 0x1d, 0x61, 0x24, 0x5b,
                0x82, 0xca, 0xcd, 0x72, 0xce, 0xdb, 0x66, 0xb5, 0x8d, 0x54, 0xbd, 0xfb, 0x23, 0xfd, 0xe8, 0x8e,
                0xaf, 0xa7, 0xb3, 0x79, 0xbe, 0x94, 0xb5, 0xb7, 0xba, 0x17, 0xb6, 0x05, 0xae, 0xce, 0x43, 0xbe,
                0x3b, 0xce, 0x6e, 0xea, 0x07, 0xdb, 0xbf, 0x0a, 0x7e, 0xeb, 0xbc, 0xc9, 0x7b, 0x62, 0x3c, 0xf5,
                0xe1, 0xce, 0xe1, 0xd9, 0x8d, 0x9c, 0xfe, 0x1f, 0xc7, 0xf8, 0xfb, 0x59, 0xc0, 0x94, 0x0b, 0x2c,
                0xd9, 0x7d, 0xbc, 0x96, 0xeb, 0xb8, 0x79, 0x22, 0x8a, 0x2e, 0xa0, 0x12, 0x1d, 0x42, 0x07, 0xb6,
                0x5d, 0xdb, 0xe1, 0xf6, 0xb1, 0x5d, 0x7b, 0x1f, 0x54, 0x52, 0x1c, 0xa3, 0x11, 0x9b, 0xf9, 0xeb,
                0xbe, 0xb3, 0x95, 0xca, 0xa5, 0x87, 0x3f, 0x31, 0x18, 0x1a, 0xc9, 0x99, 0x01, 0xec, 0xaa, 0x90,
                0xfd, 0x8a, 0x36, 0x35, 0x5e, 0x12, 0x81, 0xbe, 0x84, 0x88, 0xa1, 0x0d, 0x19, 0x2a, 0x4a, 0x66,
                0xc1, 0x59, 0x3c, 0x41, 0x83, 0x3d, 0x3d, 0xb8, 0xd4, 0xab, 0x34, 0x90, 0x06, 0x3e, 0x1a, 0x61,
                0x74, 0xbe, 0x04, 0xf5, 0x7a, 0x69, 0x1b, 0x9d, 0x56, 0xfc, 0x83, 0xb7, 0x60, 0xc1, 0x5e, 0x9d,
                0x85, 0x34, 0xfd, 0x02, 0x1a, 0xba, 0x2c, 0x09, 0x72, 0xa7, 0x4a, 0x5e, 0x18, 0xbf, 0xc0, 0x58,
                0xa7, 0x49, 0x34, 0x46, 0x61, 0x59, 0x0e, 0xe2, 0x6e, 0x9e, 0xd2, 0xdb, 0xfd, 0x72, 0x2f, 0x3c,
                0x47, 0xcc, 0x5f, 0x99, 0x62, 0xee, 0x0d, 0xf3, 0x1f, 0x30, 0x25, 0x20, 0x92, 0x15, 0x4b, 0x04,
                0xfe, 0x15, 0x19, 0x1d, 0xdc, 0x7e, 0x5c, 0x10, 0x21, 0x52, 0x21, 0x91, 0x54, 0x60, 0x8b, 0x92,
                0x41, 0x02, 0x03, 0x01, 0x00, 0x01
            )
        )

        private val DEFAULT_RSA_PRIVATE_KEY_DER = toBuffer(
            intArrayOf(
                0x30, 0x82, 0x04, 0xbf, 0x02, 0x01, 0x00, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7,
                0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x04, 0x82, 0x04, 0xa9, 0x30, 0x82, 0x04, 0xa5, 0x02, 0x01,
                0x00, 0x02, 0x82, 0x01, 0x01, 0x00, 0xb8, 0x09, 0xa7, 0x59, 0x82, 0x84, 0xec, 0x4f, 0x06, 0xfa,
                0x1c, 0xb2, 0xe1, 0x38, 0x93, 0x53, 0xbb, 0x7d, 0xd4, 0xac, 0x88, 0x1a, 0xf8, 0x25, 0x11, 0xe4,
                0xfa, 0x1d, 0x61, 0x24, 0x5b, 0x82, 0xca, 0xcd, 0x72, 0xce, 0xdb, 0x66, 0xb5, 0x8d, 0x54, 0xbd,
                0xfb, 0x23, 0xfd, 0xe8, 0x8e, 0xaf, 0xa7, 0xb3, 0x79, 0xbe, 0x94, 0xb5, 0xb7, 0xba, 0x17, 0xb6,
                0x05, 0xae, 0xce, 0x43, 0xbe, 0x3b, 0xce, 0x6e, 0xea, 0x07, 0xdb, 0xbf, 0x0a, 0x7e, 0xeb, 0xbc,
                0xc9, 0x7b, 0x62, 0x3c, 0xf5, 0xe1, 0xce, 0xe1, 0xd9, 0x8d, 0x9c, 0xfe, 0x1f, 0xc7, 0xf8, 0xfb,
                0x59, 0xc0, 0x94, 0x0b, 0x2c, 0xd9, 0x7d, 0xbc, 0x96, 0xeb, 0xb8, 0x79, 0x22, 0x8a, 0x2e, 0xa0,
                0x12, 0x1d, 0x42, 0x07, 0xb6, 0x5d, 0xdb, 0xe1, 0xf6, 0xb1, 0x5d, 0x7b, 0x1f, 0x54, 0x52, 0x1c,
                0xa3, 0x11, 0x9b, 0xf9, 0xeb, 0xbe, 0xb3, 0x95, 0xca, 0xa5, 0x87, 0x3f, 0x31, 0x18, 0x1a, 0xc9,
                0x99, 0x01, 0xec, 0xaa, 0x90, 0xfd, 0x8a, 0x36, 0x35, 0x5e, 0x12, 0x81, 0xbe, 0x84, 0x88, 0xa1,
                0x0d, 0x19, 0x2a, 0x4a, 0x66, 0xc1, 0x59, 0x3c, 0x41, 0x83, 0x3d, 0x3d, 0xb8, 0xd4, 0xab, 0x34,
                0x90, 0x06, 0x3e, 0x1a, 0x61, 0x74, 0xbe, 0x04, 0xf5, 0x7a, 0x69, 0x1b, 0x9d, 0x56, 0xfc, 0x83,
                0xb7, 0x60, 0xc1, 0x5e, 0x9d, 0x85, 0x34, 0xfd, 0x02, 0x1a, 0xba, 0x2c, 0x09, 0x72, 0xa7, 0x4a,
                0x5e, 0x18, 0xbf, 0xc0, 0x58, 0xa7, 0x49, 0x34, 0x46, 0x61, 0x59, 0x0e, 0xe2, 0x6e, 0x9e, 0xd2,
                0xdb, 0xfd, 0x72, 0x2f, 0x3c, 0x47, 0xcc, 0x5f, 0x99, 0x62, 0xee, 0x0d, 0xf3, 0x1f, 0x30, 0x25,
                0x20, 0x92, 0x15, 0x4b, 0x04, 0xfe, 0x15, 0x19, 0x1d, 0xdc, 0x7e, 0x5c, 0x10, 0x21, 0x52, 0x21,
                0x91, 0x54, 0x60, 0x8b, 0x92, 0x41, 0x02, 0x03, 0x01, 0x00, 0x01, 0x02, 0x82, 0x01, 0x01, 0x00,
                0x8a, 0x05, 0xfb, 0x73, 0x7f, 0x16, 0xaf, 0x9f, 0xa9, 0x4c, 0xe5, 0x3f, 0x26, 0xf8, 0x66, 0x4d,
                0xd2, 0xfc, 0xd1, 0x06, 0xc0, 0x60, 0xf1, 0x9f, 0xe3, 0xa6, 0xc6, 0x0a, 0x48, 0xb3, 0x9a, 0xca,
                0x21, 0xcd, 0x29, 0x80, 0x88, 0x3d, 0xa4, 0x85, 0xa5, 0x7b, 0x82, 0x21, 0x81, 0x28, 0xeb, 0xf2,
                0x43, 0x24, 0xb0, 0x76, 0xc5, 0x52, 0xef, 0xc2, 0xea, 0x4b, 0x82, 0x41, 0x92, 0xc2, 0x6d, 0xa6,
                0xae, 0xf0, 0xb2, 0x26, 0x48, 0xa1, 0x23, 0x7f, 0x02, 0xcf, 0xa8, 0x90, 0x17, 0xa2, 0x3e, 0x8a,
                0x26, 0xbd, 0x6d, 0x8a, 0xee, 0xa6, 0x0c, 0x31, 0xce, 0xc2, 0xbb, 0x92, 0x59, 0xb5, 0x73, 0xe2,
                0x7d, 0x91, 0x75, 0xe2, 0xbd, 0x8c, 0x63, 0xe2, 0x1c, 0x8b, 0xc2, 0x6a, 0x1c, 0xfe, 0x69, 0xc0,
                0x44, 0xcb, 0x58, 0x57, 0xb7, 0x13, 0x42, 0xf0, 0xdb, 0x50, 0x4c, 0xe0, 0x45, 0x09, 0x8f, 0xca,
                0x45, 0x8a, 0x06, 0xfe, 0x98, 0xd1, 0x22, 0xf5, 0x5a, 0x9a, 0xdf, 0x89, 0x17, 0xca, 0x20, 0xcc,
                0x12, 0xa9, 0x09, 0x3d, 0xd5, 0xf7, 0xe3, 0xeb, 0x08, 0x4a, 0xc4, 0x12, 0xc0, 0xb9, 0x47, 0x6c,
                0x79, 0x50, 0x66, 0xa3, 0xf8, 0xaf, 0x2c, 0xfa, 0xb4, 0x6b, 0xec, 0x03, 0xad, 0xcb, 0xda, 0x24,
                0x0c, 0x52, 0x07, 0x87, 0x88, 0xc0, 0x21, 0xf3, 0x02, 0xe8, 0x24, 0x44, 0x0f, 0xcd, 0xa0, 0xad,
                0x2f, 0x1b, 0x79, 0xab, 0x6b, 0x49, 0x4a, 0xe6, 0x3b, 0xd0, 0xad, 0xc3, 0x48, 0xb9, 0xf7, 0xf1,
                0x34, 0x09, 0xeb, 0x7a, 0xc0, 0xd5, 0x0d, 0x39, 0xd8, 0x45, 0xce, 0x36, 0x7a, 0xd8, 0xde, 0x3c,
                0xb0, 0x21, 0x96, 0x97, 0x8a, 0xff, 0x8b, 0x23, 0x60, 0x4f, 0xf0, 0x3d, 0xd7, 0x8f, 0xf3, 0x2c,
                0xcb, 0x1d, 0x48, 0x3f, 0x86, 0xc4, 0xa9, 0x00, 0xf2, 0x23, 0x2d, 0x72, 0x4d, 0x66, 0xa5, 0x01,
                0x02, 0x81, 0x81, 0x00, 0xdc, 0x4f, 0x99, 0x44, 0x0d, 0x7f, 0x59, 0x46, 0x1e, 0x8f, 0xe7, 0x2d,
                0x8d, 0xdd, 0x54, 0xc0, 0xf7, 0xfa, 0x46, 0x0d, 0x9d, 0x35, 0x03, 0xf1, 0x7c, 0x12, 0xf3, 0x5a,
                0x9d, 0x83, 0xcf, 0xdd, 0x37, 0x21, 0x7c, 0xb7, 0xee, 0xc3, 0x39, 0xd2, 0x75, 0x8f, 0xb2, 0x2d,
                0x6f, 0xec, 0xc6, 0x03, 0x55, 0xd7, 0x00, 0x67, 0xd3, 0x9b, 0xa2, 0x68, 0x50, 0x6f, 0x9e, 0x28,
                0xa4, 0x76, 0x39, 0x2b, 0xb2, 0x65, 0xcc, 0x72, 0x82, 0x93, 0xa0, 0xcf, 0x10, 0x05, 0x6a, 0x75,
                0xca, 0x85, 0x35, 0x99, 0xb0, 0xa6, 0xc6, 0xef, 0x4c, 0x4d, 0x99, 0x7d, 0x2c, 0x38, 0x01, 0x21,
                0xb5, 0x31, 0xac, 0x80, 0x54, 0xc4, 0x18, 0x4b, 0xfd, 0xef, 0xb3, 0x30, 0x22, 0x51, 0x5a, 0xea,
                0x7d, 0x9b, 0xb2, 0x9d, 0xcb, 0xba, 0x3f, 0xc0, 0x1a, 0x6b, 0xcd, 0xb0, 0xe6, 0x2f, 0x04, 0x33,
                0xd7, 0x3a, 0x49, 0x71, 0x02, 0x81, 0x81, 0x00, 0xd5, 0xd9, 0xc9, 0x70, 0x1a, 0x13, 0xb3, 0x39,
                0x24, 0x02, 0xee, 0xb0, 0xbb, 0x84, 0x17, 0x12, 0xc6, 0xbd, 0x65, 0x73, 0xe9, 0x34, 0x5d, 0x43,
                0xff, 0xdc, 0xf8, 0x55, 0xaf, 0x2a, 0xb9, 0xe1, 0xfa, 0x71, 0x65, 0x4e, 0x50, 0x0f, 0xa4, 0x3b,
                0xe5, 0x68, 0xf2, 0x49, 0x71, 0xaf, 0x15, 0x88, 0xd7, 0xaf, 0xc4, 0x9d, 0x94, 0x84, 0x6b, 0x5b,
                0x10, 0xd5, 0xc0, 0xaa, 0x0c, 0x13, 0x62, 0x99, 0xc0, 0x8b, 0xfc, 0x90, 0x0f, 0x87, 0x40, 0x4d,
                0x58, 0x88, 0xbd, 0xe2, 0xba, 0x3e, 0x7e, 0x2d, 0xd7, 0x69, 0xa9, 0x3c, 0x09, 0x64, 0x31, 0xb6,
                0xcc, 0x4d, 0x1f, 0x23, 0xb6, 0x9e, 0x65, 0xd6, 0x81, 0xdc, 0x85, 0xcc, 0x1e, 0xf1, 0x0b, 0x84,
                0x38, 0xab, 0x93, 0x5f, 0x9f, 0x92, 0x4e, 0x93, 0x46, 0x95, 0x6b, 0x3e, 0xb6, 0xc3, 0x1b, 0xd7,
                0x69, 0xa1, 0x0a, 0x97, 0x37, 0x78, 0xed, 0xd1, 0x02, 0x81, 0x80, 0x33, 0x18, 0xc3, 0x13, 0x65,
                0x8e, 0x03, 0xc6, 0x9f, 0x90, 0x00, 0xae, 0x30, 0x19, 0x05, 0x6f, 0x3c, 0x14, 0x6f, 0xea, 0xf8,
                0x6b, 0x33, 0x5e, 0xee, 0xc7, 0xf6, 0x69, 0x2d, 0xdf, 0x44, 0x76, 0xaa, 0x32, 0xba, 0x1a, 0x6e,
                0xe6, 0x18, 0xa3, 0x17, 0x61, 0x1c, 0x92, 0x2d, 0x43, 0x5d, 0x29, 0xa8, 0xdf, 0x14, 0xd8, 0xff,
                0xdb, 0x38, 0xef, 0xb8, 0xb8, 0x2a, 0x96, 0x82, 0x8e, 0x68, 0xf4, 0x19, 0x8c, 0x42, 0xbe, 0xcc,
                0x4a, 0x31, 0x21, 0xd5, 0x35, 0x6c, 0x5b, 0xa5, 0x7c, 0xff, 0xd1, 0x85, 0x87, 0x28, 0xdc, 0x97,
                0x75, 0xe8, 0x03, 0x80, 0x1d, 0xfd, 0x25, 0x34, 0x41, 0x31, 0x21, 0x12, 0x87, 0xe8, 0x9a, 0xb7,
                0x6a, 0xc0, 0xc4, 0x89, 0x31, 0x15, 0x45, 0x0d, 0x9c, 0xee, 0xf0, 0x6a, 0x2f, 0xe8, 0x59, 0x45,
                0xc7, 0x7b, 0x0d, 0x6c, 0x55, 0xbb, 0x43, 0xca, 0xc7, 0x5a, 0x01, 0x02, 0x81, 0x81, 0x00, 0xab,
                0xf4, 0xd5, 0xcf, 0x78, 0x88, 0x82, 0xc2, 0xdd, 0xbc, 0x25, 0xe6, 0xa2, 0xc1, 0xd2, 0x33, 0xdc,
                0xef, 0x0a, 0x97, 0x2b, 0xdc, 0x59, 0x6a, 0x86, 0x61, 0x4e, 0xa6, 0xc7, 0x95, 0x99, 0xa6, 0xa6,
                0x55, 0x6c, 0x5a, 0x8e, 0x72, 0x25, 0x63, 0xac, 0x52, 0xb9, 0x10, 0x69, 0x83, 0x99, 0xd3, 0x51,
                0x6c, 0x1a, 0xb3, 0x83, 0x6a, 0xff, 0x50, 0x58, 0xb7, 0x28, 0x97, 0x13, 0xe2, 0xba, 0x94, 0x5b,
                0x89, 0xb4, 0xea, 0xba, 0x31, 0xcd, 0x78, 0xe4, 0x4a, 0x00, 0x36, 0x42, 0x00, 0x62, 0x41, 0xc6,
                0x47, 0x46, 0x37, 0xea, 0x6d, 0x50, 0xb4, 0x66, 0x8f, 0x55, 0x0c, 0xc8, 0x99, 0x91, 0xd5, 0xec,
                0xd2, 0x40, 0x1c, 0x24, 0x7d, 0x3a, 0xff, 0x74, 0xfa, 0x32, 0x24, 0xe0, 0x11, 0x2b, 0x71, 0xad,
                0x7e, 0x14, 0xa0, 0x77, 0x21, 0x68, 0x4f, 0xcc, 0xb6, 0x1b, 0xe8, 0x00, 0x49, 0x13, 0x21, 0x02,
                0x81, 0x81, 0x00, 0xb6, 0x18, 0x73, 0x59, 0x2c, 0x4f, 0x92, 0xac, 0xa2, 0x2e, 0x5f, 0xb6, 0xbe,
                0x78, 0x5d, 0x47, 0x71, 0x04, 0x92, 0xf0, 0xd7, 0xe8, 0xc5, 0x7a, 0x84, 0x6b, 0xb8, 0xb4, 0x30,
                0x1f, 0xd8, 0x0d, 0x58, 0xd0, 0x64, 0x80, 0xa7, 0x21, 0x1a, 0x48, 0x00, 0x37, 0xd6, 0x19, 0x71,
                0xbb, 0x91, 0x20, 0x9d, 0xe2, 0xc3, 0xec, 0xdb, 0x36, 0x1c, 0xca, 0x48, 0x7d, 0x03, 0x32, 0x74,
                0x1e, 0x65, 0x73, 0x02, 0x90, 0x73, 0xd8, 0x3f, 0xb5, 0x52, 0x35, 0x79, 0x1c, 0xee, 0x93, 0xa3,
                0x32, 0x8b, 0xed, 0x89, 0x98, 0xf1, 0x0c, 0xd8, 0x12, 0xf2, 0x89, 0x7f, 0x32, 0x23, 0xec, 0x67,
                0x66, 0x52, 0x83, 0x89, 0x99, 0x5e, 0x42, 0x2b, 0x42, 0x4b, 0x84, 0x50, 0x1b, 0x3e, 0x47, 0x6d,
                0x74, 0xfb, 0xd1, 0xa6, 0x10, 0x20, 0x6c, 0x6e, 0xbe, 0x44, 0x3f, 0xb9, 0xfe, 0xbc, 0x8d, 0xda,
                0xcb, 0xea, 0x8f
            )
        )

        /* Convert the int array to a ByteBuffer. */
        private fun toBuffer(array: IntArray): ByteBuffer {
            val result = ByteBuffer.allocate(array.size)
            for (i in array.indices) result.put((array[i] and 0xff).toByte())
            result.flip()
            return result
        }
        private const val useNDN = false
    }

    private inner class MetadataResults : OnData, OnTimeout,
        OnRegisterFailed {
        override fun onData(interest: Interest, data: Data) {
            Log.i(TAG, "Got data packet with name " + data.name.toUri())
            Log.i(TAG, "Got data packet with Content " + data.content)
            val metaInfoName = Name("$producerName/andy/metadata")
            if (metaInfoName.isPrefixOf(data.name)) {
                lastAnimationName = data.content.toString()
            }
        }

        override fun onTimeout(interest: Interest) {
            Log.i(TAG, "Time out for interest " + interest.name.toUri())
        }

        override fun onRegisterFailed(name: Name) {
            Log.i(TAG, "onRegisterFailed for interest " + name.toUri())
        }
    }


    private inner class AnimationResults : OnData, OnTimeout,
        OnRegisterFailed {
        override fun onData(interest: Interest, data: Data) {
            Log.i(TAG, "Got data packet with name " + data.name.toUri())
            Log.i(TAG, "Got data packet with Content " + data.content)
            val animationName = Name("$producerName/andy/animation")
            if (animationName.isPrefixOf(data.name)) {
                currentAnimation = data.content.toString()
            }
        }

        override fun onTimeout(interest: Interest) {
            Log.i(TAG, "Time out for interest " + interest.name.toUri())
        }

        override fun onRegisterFailed(name: Name) {
            Log.i(TAG, "onRegisterFailed for interest " + name.toUri())
        }
    }

    private inner class FrameThread : Thread() {
        override fun run() {
            try {
                // ===============Retrieve Frame========================
                val keyChain = KeyChain("pib-memory:", "tpm-memory:")
                keyChain.importSafeBag(
                    SafeBag(
                        Name("/testname/KEY/123"),
                        Blob(DEFAULT_RSA_PRIVATE_KEY_DER, false),
                        Blob(DEFAULT_RSA_PUBLIC_KEY_DER, false)
                    )
                )
                val metadataResults = MetadataResults()
                val options = SegmentFetcher.Options()
                val maxTimeOut = 10000
                options.maxTimeout = maxTimeOut
                while (true) {
                    Log.i(TAG, "New animation request")
                    while (lastAnimationName == null) {
                        val metadataName = Name("$producerName/andy/metadata").appendTimestamp(
                            System.currentTimeMillis()
                        )
                        face!!.expressInterest(metadataName, metadataResults, metadataResults)
                        face!!.processEvents()
                        // We need to sleep for a few milliseconds so we don't use 100% of the CPU.
                        sleep(300)
                    }

                    // Get the animation with the lastAnimation name
                    Log.d(TAG, "Latest Animation: $lastAnimationName")
                    val animationDataName = Name("$producerName/andy/animation/$lastAnimationName")
                    val animationResults = AnimationResults()
                    face!!.expressInterest(animationDataName, animationResults, animationResults)
                    face!!.processEvents()
                    sleep(500)
                    lastAnimationName = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "exception: " + e.message)
                e.printStackTrace()
            }
        }
    }


    init {
        if (useNDN){
            face = Face("localhost")
            val thread = FrameThread()
            thread.start()
        }

        ModelRenderable.builder()
            .setSource(context.applicationContext, R.raw.andy_dance)
            .build()
            .thenAccept { currentRenderable ->
                renderable = currentRenderable
                andyRenderable = currentRenderable
            }
        ModelRenderable.builder()
            .setSource(context.applicationContext, R.raw.baseball_cap)
            .build()
            .thenAccept { currentRenderable ->
                hatRenderable = currentRenderable
            }
    }

    private fun traverseChildren(node: Node) {
        val childrenNodes = node.children
        Log.i(TAG, "Children node name ${node.name}" )
        for (n in childrenNodes){
            traverseChildren(n)
        }
    }

    override fun onTap(hitTestResult: HitTestResult?, motionEvent: MotionEvent?) {
        super.onTap(hitTestResult, motionEvent)
        if (animator == null || !animator?.isRunning!!) {
            andy = SkeletonNode()
            andy!!.renderable = this.renderable
            hatNode = Node()
            // Attach a node to the bone.  This node takes the internal scale of the bone, so any
            // renderables should be added to child nodes with the world pose reset.
            // This also allows for tweaking the position relative to the bone.

            // Attach a node to the bone.  This node takes the internal scale of the bone, so any
            // renderables should be added to child nodes with the world pose reset.
            // This also allows for tweaking the position relative to the bone.
            val boneNode = Node()
            boneNode.setParent(andy)
            andy!!.setBoneAttachment(
                HAT_BONE_NAME,
                boneNode
            )
            andy!!.setParent(this)
            hatNode!!.renderable = hatRenderable
            hatNode!!.setParent(boneNode)
            hatNode!!.worldScale = Vector3.one()
            hatNode!!.worldRotation = Quaternion.identity()
            val pos = hatNode!!.worldPosition
            // Lower the hat down over the antennae.
            pos.y -= .1f
            hatNode!!.worldPosition = pos

            // Log the children nodes of andy
            Log.i(TAG, "Submesh count of Andy ${andyRenderable?.submeshCount}")
            for (i in 0 until andyRenderable?.submeshCount!!){
                Log.i(TAG, "Submesh names of Andy ${andyRenderable?.getSubmeshName(i)}")
            }
            traverseChildren(andy as Node)

            nextAnimation = when {
                currentAnimation != null -> currentAnimation!!.toInt()
                else -> (nextAnimation + 1) % andyRenderable!!.animationDataCount
            }
            val data = andyRenderable!!.getAnimationData(nextAnimation)
            animator = ModelAnimator(data, andyRenderable)
            animator!!.start()
        }
    }
}

typealias CollisionPlane = com.google.ar.sceneform.collision.Plane

class Drawing(
    val isFromTouch: Boolean,
    private val plane: CollisionPlane?,
    properties: MaterialProperties,
    coordinator: Coordinator,
    settings: Settings
) : MaterialNode("Drawing", properties, coordinator, settings) {

    companion object {

        private const val RADIUS = 0.005F
        private const val PLANE_ANCHORING_DISTANCE = 2F
        private const val DEFAULT_DRAWING_DISTANCE = 0.5F

        private fun hit(frame: Frame, x: Float, y: Float): HitResult? {
            return frame.hitTest(x, y).firstOrNull {
                (it.trackable as? Plane)?.isPoseInPolygon(it.hitPose) == true && it.distance <= PLANE_ANCHORING_DISTANCE
            }
        }

        private fun pose(camera: Camera, x: Float, y: Float): Pose {
            val ray = camera.screenPointToRay(x, y)
            val point = ray.getPoint(DEFAULT_DRAWING_DISTANCE)
            return Pose.makeTranslation(point.x, point.y, point.z)
        }

        private fun plane(hitResult: HitResult?): CollisionPlane? {
            return (hitResult?.trackable as? Plane)?.let {
                val pose = it.centerPose
                val normal = Quaternion.rotateVector(pose.rotation(), Vector3.up())
                CollisionPlane(pose.translation(), normal)
            }
        }

        fun create(
            x: Float,
            y: Float,
            fromTouch: Boolean,
            properties: MaterialProperties,
            ar: ArSceneView,
            coordinator: Coordinator,
            settings: Settings
        ): Drawing? {
            val context = ar.context
            val session = ar.session ?: return null
            val scene = ar.scene ?: return null
            val frame = ar.arFrame ?: return null
            if (frame.camera.trackingState != TRACKING) return null

            val hit = hit(frame, x, y)
            val pose = hit?.hitPose ?: pose(scene.camera, x, y)
            val plane = plane(hit)
            val anchor = hit?.createAnchor() ?: session.createAnchor(pose)

            return Drawing(fromTouch, plane, properties, coordinator, settings).apply {
                makeOpaqueWithColor(
                    context.applicationContext,
                    properties.color.toArColor()
                ).thenAccept { material = it }
                attach(anchor, scene)
                extend(x, y)
            }
        }
    }

    private val line = LineSimplifier()
    private var material: Material? = null
        set(value) {
            field = value?.apply { properties.update(this) }
            render()
        }

    private fun append(pointInWorld: Vector3) {
        val pointInLocal = (parent as AnchorNode).worldToLocalPoint(pointInWorld)
        line.append(pointInLocal)
        render()
    }

    private fun render() {
        val definition =
            ExtrudedCylinder.makeExtrudedCylinder(RADIUS, line.points, material ?: return) ?: return
        if (renderable == null) {
            ModelRenderable.builder().setSource(definition).build().thenAccept { renderable = it }
        } else {
            renderable?.updateFromDefinition(definition)
        }
    }

    fun extend(x: Float, y: Float) {
        val ray = scene?.camera?.screenPointToRay(x, y) ?: return
        if (plane != null) {
            val rayHit = RayHit()
            if (plane.rayIntersection(ray, rayHit)) {
                append(rayHit.point)
            }
        } else {
            append(ray.getPoint(DEFAULT_DRAWING_DISTANCE))
        }
    }

    fun deleteIfEmpty() = if (line.points.size < 2) detach() else Unit

}

/*
Link is the class for importing models from URL
 */
class Link(
    context: Context,
    uri: Uri,
    coordinator: Coordinator,
    settings: Settings
) : Nodes("Link", coordinator, settings) {
    companion object {

        fun warmup(_context: Context, uri: Uri): CompletableFuture<ModelRenderable> {
            val context = _context.applicationContext
            return ModelRenderable.builder().apply {
                when {
                    uri.toString().endsWith("GLTF", ignoreCase = true) -> {
                        setSource(
                            context,
                            RenderableSource.builder().setSource(context, uri, GLTF2).build()
                        )
                    }
                    uri.toString().endsWith("GLB", ignoreCase = true) -> {
                        setSource(
                            context,
                            RenderableSource.builder().setSource(context, uri, GLB).build()
                        )
                    }
                    else -> setSource(context, uri)
                }
            }
                .setRegistryId(uri.toString())
                .build()
                .exceptionally {
                    Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                    Log.e("Link", "create", it)
                    null
                }
        }
    }

    init {
        warmup(context, uri).thenAccept {
            renderable = it
            // TODO: Check the node structure of the received gltf node
        }
    }
}

class Augmented(
    context: Context,
    private val image: AugmentedImage,
    coordinator: Coordinator,
    settings: Settings
) : Nodes("Augmented image", coordinator, settings) {

    companion object {

        private val references: MutableMap<AugmentedImage, Nodes> = mutableMapOf()

        fun target(context: Context) = try {
            context.applicationContext.assets.open("augmented_image_target.png")
        } catch (e: Exception) {
            null
        }?.let { BitmapFactory.decodeStream(it) }

        fun update(
            context: Context,
            image: AugmentedImage,
            coordinator: Coordinator,
            settings: Settings
        ): Augmented? {
            val node = references[image]
            when (image.trackingState) {
                TRACKING -> if (node == null && image.trackingMethod == FULL_TRACKING) {
                    return Augmented(context.applicationContext, image, coordinator, settings)
                }
                STOPPED -> node?.detach()
                PAUSED -> Unit
                else -> Unit
            }
            return null
        }

    }

    init {
        ModelRenderable.builder()
            .setSource(context.applicationContext, R.raw.rocket)
            .build()
            .thenAccept {
                renderable = it
            }
    }

    override fun attach(anchor: Anchor, scene: Scene, focus: Boolean) {
        super.attach(anchor, scene, focus)
        references[image] = this
    }

    override fun detach() {
        super.detach()
        references.remove(image)
    }

}

class CloudAnchor(
    context: Context,
    private val session: Session,
    coordinator: Coordinator,
    settings: Settings
) : Nodes("Cloud Anchor", coordinator, settings) {

    private var lastState: Anchor.CloudAnchorState? = null

    companion object {

        fun resolve(
            id: String,
            context: Context,
            ar: ArSceneView,
            coordinator: Coordinator,
            settings: Settings
        ): CloudAnchor? {
            if (ar.arFrame?.camera?.trackingState != TRACKING) return null
            val session = ar.session ?: return null
            val anchor = session.resolveCloudAnchor(id)
            return CloudAnchor(
                context.applicationContext,
                session,
                coordinator,
                settings
            ).also { it.attach(anchor, ar.scene) }
        }

    }

    init {
        translationController.isEnabled = false
        rotationController.isEnabled = false
        scaleController.isEnabled = false

        ViewRenderable.builder()
            .setView(context.applicationContext, R.layout.view_renderable_cloud_anchor)
            .build()
            .thenAccept { renderable = it }
    }

    fun id(): String? = anchor()?.cloudAnchorId.takeUnless { it.isNullOrBlank() }

    fun state() = anchor()?.cloudAnchorState

    override fun attach(anchor: Anchor, scene: Scene, focus: Boolean) {
        super.attach(anchor, scene, focus)
        if (anchor.cloudAnchorState == NONE) {
            (parent as? AnchorNode)?.apply {
                this.anchor?.detach()
                this.anchor = session.hostCloudAnchor(anchor)
            }
        }
    }

    override fun onUpdate(frameTime: FrameTime) {
        super.onUpdate(frameTime)
        state()?.let {
            if (it != lastState) {
                lastState = it
                update(renderable)
            }
        }
    }

    override fun setRenderable(renderable: Renderable?) {
        super.setRenderable(renderable)
        renderable?.apply {
            update(this)
            isShadowCaster = false
            isShadowReceiver = false
        }
    }

    private fun update(renderable: Renderable?) {
        ((renderable as? ViewRenderable)?.view as? ImageView)?.setImageResource(state().icon())
    }

    override fun statusIcon(): Int = when (val state = state()) {
        NONE -> android.R.drawable.presence_invisible
        TASK_IN_PROGRESS -> android.R.drawable.presence_away
        SUCCESS -> super.statusIcon()
        else -> if (state?.isError == true) android.R.drawable.presence_busy else android.R.drawable.presence_invisible
    }

    private fun Anchor.CloudAnchorState?.icon(): Int = when (this) {
        NONE -> R.drawable.ic_cloud_anchor
        TASK_IN_PROGRESS -> R.drawable.ic_cloud_anchor_sync
        SUCCESS -> R.drawable.ic_cloud_anchor_success
        else -> if (this?.isError == true) R.drawable.ic_cloud_anchor_error else R.drawable.ic_cloud_anchor_unknown
    }

    fun copyToClipboard(context: Context) {
        val clip =
            ClipData.newPlainText(context.getString(R.string.cloud_anchor_id_label), id() ?: return)
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(clip)
        val message = buildSpannedString {
            inSpans(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)) {
                append(context.getText(R.string.cloud_anchor_id_copied_to_clipboard))
                append("\n")
                bold { append(id()) }
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

}

class Video(
    val context: Context,
    coordinator: Coordinator,
    settings: Settings
) : Nodes("Video", coordinator, settings), MediaPlayer.OnVideoSizeChangedListener {

    private var mediaPlayer: MediaPlayer? = null
    private val texture = ExternalTexture()

    /* Use a child node to keep the video dimensions independent of scaling */
    private val video: Node = Node().apply { setParent(this@Video) }

    init {
        ModelRenderable.builder()
            .setSource(context.applicationContext, R.raw.chroma_key_video)
            .build()
            .thenAccept {
                it.material.setExternalTexture("videoTexture", texture)
//                it.material.setFloat4("keyColor", Color(0.1843f, 1.0f, 0.098f)) // Green screen
//                it.material.setFloat4("keyColor", Color(1.0f, 1.0f, 1.0f))      // White
                it.material.setBoolean("disableChromaKey", true)
                video.renderable = it
            }
    }

    override fun onActivate() {
        val source = "NDN"
        val url = "https://www.rmp-streaming.com/media/big-buck-bunny-720p.mp4"
//        val url = "http://192.168.0.7:62222/stream-server-yolo/NDN-server/chroma-key-video/big-buck-bunny-720p.mp4"
//        val url = "http://192.168.0.7:62222/stream-server-yolo/NDN-server/chroma-key-video/demo.mp4"
        mediaPlayer = when (source) {
            "Tiger" ->  MediaPlayer.create(context.applicationContext, R.raw.video).apply {
                isLooping = true
                setSurface(texture.surface)
                setOnVideoSizeChangedListener(this@Video)
                start()
            }
            "http" -> MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                prepare() // might take long! (for buffering, etc)
                setSurface(texture.surface)
                setOnVideoSizeChangedListener(this@Video)
                start()
            }
            else -> {
                /* Load customized data source */
                val dataSource = VideoDataSource()
                dataSource.setVideoURL(url)
                dataSource.downloadVideo(object : VideoDownloadListener {
                    override fun onVideoDownloaded() {
                        mediaPlayer!!.prepareAsync()
                    }
                    override fun onVideoDownloadError(e: java.lang.Exception?) {
                        Log.d("Video Download Error", e.toString())
                    }
                })
                MediaPlayer().apply {
                    setDataSource(dataSource)
                    setSurface(texture.surface)
                    setOnVideoSizeChangedListener(this@Video)
                    setOnPreparedListener(OnPreparedListener { mp -> mp.start() })
                }
            }
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    fun toggle() {
        mediaPlayer?.let {
            if (it.isPlaying) it.pause() else it.start()
        }
    }

    override fun onDeactivate() {
        mediaPlayer?.setOnVideoSizeChangedListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onVideoSizeChanged(mp: MediaPlayer, width: Int, height: Int) {
        if (width == 0 || height == 0) return
        mp.setOnVideoSizeChangedListener(null)
        video.localScale = when {
            width > height -> Vector3(1F, height / width.toFloat(), 1F)
            width < height -> Vector3(width / height.toFloat(), 1F, 1F)
            else -> Vector3.one()
        }
    }

}



class NdnVideo(
    val context: Context,
    coordinator: Coordinator,
    settings: Settings
) : Nodes("NdnVideo", coordinator, settings), MediaPlayer.OnVideoSizeChangedListener {

    private var mediaPlayer: MediaPlayer? = null
    private val texture = ExternalTexture()

    /* Use a child node to keep the video dimensions independent of scaling */
    private val video: Node = Node().apply { setParent(this@NdnVideo) }

    init {
        ModelRenderable.builder()
            .setSource(context.applicationContext, R.raw.chroma_key_video)
            .build()
            .thenAccept {
                it.material.setExternalTexture("videoTexture", texture)
//                it.material.setFloat4("keyColor", Color(0.1843f, 1.0f, 0.098f)) // Green screen
//                it.material.setFloat4("keyColor", Color(1.0f, 1.0f, 1.0f))      // White
                it.material.setBoolean("disableChromaKey", true)
                video.renderable = it
            }
    }

    override fun onActivate() {
        val source = "NDN"
        val url = "https://www.rmp-streaming.com/media/big-buck-bunny-720p.mp4"
        mediaPlayer = MediaPlayer.create(context.applicationContext, R.raw.video).apply {
            isLooping = true
            setSurface(texture.surface)
            setOnVideoSizeChangedListener(this@NdnVideo)
            start()
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    fun toggle() {
        mediaPlayer?.let {
            if (it.isPlaying) it.pause() else it.start()
        }
    }

    override fun onDeactivate() {
        mediaPlayer?.setOnVideoSizeChangedListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onVideoSizeChanged(mp: MediaPlayer, width: Int, height: Int) {
        if (width == 0 || height == 0) return
        mp.setOnVideoSizeChangedListener(null)
        video.localScale = when {
            width > height -> Vector3(1F, height / width.toFloat(), 1F)
            width < height -> Vector3(width / height.toFloat(), 1F, 1F)
            else -> Vector3.one()
        }
    }

}

