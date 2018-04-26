package eu.zderadicka.audioserve.fragments

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import eu.zderadicka.audioserve.utils.ifStoppedOrDead
import android.os.Parcelable
import android.support.v4.content.ContextCompat
import android.widget.*
import eu.zderadicka.audioserve.MEDIA_CACHE_DELETED
import eu.zderadicka.audioserve.MEDIA_FULLY_CACHED
import eu.zderadicka.audioserve.PLAYER_NOT_READY
import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.ui.SwipeRevealLayout


// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
const val ARG_FOLDER_PATH = "folder-path"
const val ARG_FOLDER_NAME = "folder-name"

const val FOLDER_VIEW_STATE_KEY = "eu.zderadicka.audiserve.folderViewKey"

const val ITEM_TYPE_FOLDER = 0
const val ITEM_TYPE_FILE = 1
const val ITEM_TYPE_BOOKMARK = 2

enum class ItemAction {
    Open,
    Download
}

private const val LOG_TAG = "FolderFragment"

//TODO icon for item type - folder or audio file
// TODO icon for currently played icon - that ice equlizer bar from Universal player
class FolderItemViewHolder(itemView: View, val viewType: Int, val clickCB: (Int, ItemAction) -> Unit) : RecyclerView.ViewHolder(itemView) {

    var itemName: TextView = itemView.findViewById(R.id.folderItemName)
    var durationView: TextView? = null
    var bitRateView: TextView? = null
    var transcodedIcon: ImageView? = null
    var cachedIcon: ImageView? = null
    var positionView: TextView? = null
    var lastListenedView: TextView? = null
    var folderPathView: TextView? = null
    var contentView: View? = null
    var itemContainer: View? = null
    var downloadButton: ImageButton? = null
    var extensionView: TextView? = null
    var isFile = false
    private set
    var isBookmark = false
    private set

    init {
        itemView.setOnClickListener { clickCB(adapterPosition, ItemAction.Open) }

        if (viewType == ITEM_TYPE_FILE) {
            contentView = itemView.findViewById(R.id.contentView)
            itemContainer = itemView.findViewById(R.id.itemContainer)
            contentView?.setOnClickListener { clickCB(adapterPosition, ItemAction.Open) }
            downloadButton = itemView.findViewById(R.id.downloadButton)
            downloadButton?.setOnClickListener{
                //val animator = ObjectAnimator.ofInt(itemContainer, "left", 0)
                //animator.start()
                (itemView as SwipeRevealLayout).close(true)
                clickCB(adapterPosition, ItemAction.Download)
            }

            durationView = itemView.findViewById(R.id.durationView)
            bitRateView = itemView.findViewById(R.id.lastListenedView)
            transcodedIcon = itemView.findViewById(R.id.transcodedIcon)
            cachedIcon = itemView.findViewById(R.id.cachedIcon)
            extensionView = itemView.findViewById(R.id.extesionView)
            isFile = true
        } else if (viewType == ITEM_TYPE_BOOKMARK) {
            durationView = itemView.findViewById(R.id.durationView)
            positionView = itemView.findViewById(R.id.positionView)
            lastListenedView = itemView.findViewById(R.id.lastListenedView)
            folderPathView = itemView.findViewById(R.id.folderPathView)
            isBookmark = true
        }

    }
}


class FolderAdapter(val context: Context,
                    private val itemCb: (MediaItem, ItemAction) -> Unit)
    : RecyclerView.Adapter<FolderItemViewHolder>() {

    private var items: List<MediaItem>? = null
    internal var nowPlaying: Int = -1
    private var pendingMediaId: String? = null  // if we got now playing metadata, but list is not loaded yet
    private val idMap: HashMap<String,Int> = HashMap()

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): FolderItemViewHolder {
        val inflater = LayoutInflater.from(parent?.context)
        var viewId = R.layout.folder_item
        if (viewType == ITEM_TYPE_FILE) {
            viewId = R.layout.file_item
        } else if (viewType == ITEM_TYPE_BOOKMARK) {
            viewId = R.layout.bookmark_item
        }
        val view = inflater.inflate(viewId, parent, false)
        return FolderItemViewHolder(view, viewType, this::onItemClicked)

    }

    private fun onItemClicked(index: Int, action: ItemAction) {

        val item = items?.get(index)
        if (item != null) {
            itemCb(item, action)
        }

    }

    override fun getItemCount(): Int {
        return items?.size ?: 0
    }

    override fun getItemViewType(position: Int): Int {
        val item = items?.get(position)
        if (item == null) {
            return ITEM_TYPE_FOLDER
        }
        return if ( item.isPlayable ) {
            if (item.description.extras?.getBoolean(METADATA_KEY_IS_BOOKMARK) == true) ITEM_TYPE_BOOKMARK
                else ITEM_TYPE_FILE
        } else {
            ITEM_TYPE_FOLDER
        }
    }

    override fun onBindViewHolder(holder: FolderItemViewHolder?, position: Int) {
        val item = items?.get(position)
        if (item == null) return
        holder!!.itemName.text = item.description.title

        if ((holder.isFile || holder.isBookmark) && item.isPlayable) {
            holder.durationView?.text =
                    DateUtils.formatElapsedTime((item.description.extras?.getLong(METADATA_KEY_DURATION)
                            ?: 0) / 1000L)
        }

        if (holder.isFile) {

            (holder.itemView as SwipeRevealLayout).close(false)
            if (position == nowPlaying) {
                holder.contentView?.setBackgroundColor(ContextCompat.getColor(context,R.color.colorAccentLight))
            } else {
                holder.contentView?.setBackgroundColor(ContextCompat.getColor(context, R.color.colorListBackground))
            }

            holder.extensionView?.text = item.description.extras?.getString(METADATA_KEY_EXTENSION,"")

            holder.bitRateView?.text =
                    item.description.extras?.getInt(METADATA_KEY_BITRATE)?.toString()?:"?"

            if (item.description.extras?.getBoolean(METADATA_KEY_TRANSCODED)?: false) {
                holder.transcodedIcon?.visibility = View.VISIBLE
            } else {
                holder.transcodedIcon?.visibility = View.INVISIBLE
            }

            if (item.description.extras?.getBoolean(METADATA_KEY_CACHED)?: false) {
                holder.cachedIcon?.visibility = View.VISIBLE
            } else {
                holder.cachedIcon?.visibility = View.INVISIBLE
            }
        } else if (holder.isBookmark) {
            holder.positionView?.text = DateUtils.formatElapsedTime((
                    item.description.extras?.getLong(METADATA_KEY_LAST_POSITION)?: 0) / 1000L)

            holder.lastListenedView?.text = DateUtils.getRelativeTimeSpanString(
                    item.description.extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP)?:0,
                    System.currentTimeMillis(),
                    0
            )

            holder.folderPathView?.text = item.description.subtitle

        }
    }

    fun changeData(newData: List<MediaItem>) {
        items = newData
        idMap.clear()
        for (i in 0 until newData.size) {
            idMap.put(newData.get(i).mediaId!!, i)
        }
        notifyDataSetChanged()
        if (pendingMediaId != null) updateNowPlaying(pendingMediaId!!)
    }

    fun updatedCached(mediaId: String, cached: Boolean) {
        val idx = idMap.get(mediaId)
        if (idx != null) {
            items?.get(idx)?.description?.extras?.putBoolean(METADATA_KEY_CACHED, cached)
            notifyItemChanged(idx)
        }
    }

    fun updateNowPlaying(mediaId: String): Int {
        val oldPlaying = nowPlaying
        val idx = idMap.get(mediaId)
        nowPlaying = if (idx == null) -1 else idx
        if (nowPlaying >= 0 && nowPlaying != oldPlaying) {
            notifyItemChanged(nowPlaying)
            if (oldPlaying >= 0) notifyItemChanged(oldPlaying)
            pendingMediaId = null
        } else if (nowPlaying < 0){
            pendingMediaId = mediaId
        }
        return nowPlaying
    }

    fun resetNowPlaying() {
        if (nowPlaying >= 0) {
            nowPlaying = -1
            notifyDataSetChanged()
        }
    }
}


interface MediaActivity {
    fun onItemClicked(item: MediaItem, action: ItemAction)
    fun onFolderLoaded(folderId: String, folderDetails: Bundle?, error: Boolean, empty: Boolean)
    val mediaBrowser: MediaBrowserCompat
}

interface TopActivity {
    fun setFolderTitle(title: String)
}



class FolderFragment : MediaFragment() {
    lateinit var folderId: String
    private set

    lateinit var folderName: String
    private set

    private var mediaActivity: MediaActivity? = null
    private lateinit var adapter: FolderAdapter

    private lateinit var folderView: RecyclerView
    private lateinit var loadingProgress: ProgressBar
    override val mCallback = object: MediaControllerCompat.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (metadata == null) {
                return
            }
            Log.d(LOG_TAG, "Received metadata state change to mediaId=${metadata.description.mediaId} song=${metadata.description.title}")
            if (metadata.description.mediaId != null) {
                adapter.updateNowPlaying(metadata.description.mediaId!!)
                scrollToNowPlaying()
            } else {
                Log.w(LOG_TAG,"Metadata should always contain mediaID :  ${metadata.description}")
            }
        }

    override  fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
        super.onPlaybackStateChanged(state)
        ifStoppedOrDead(state,
                {
            adapter.resetNowPlaying()
        })
    }

        override fun onSessionEvent(event: String?, extras: Bundle?) {
            super.onSessionEvent(event, extras)
            when (event) {
            MEDIA_FULLY_CACHED, MEDIA_CACHE_DELETED -> {
                val cached = event == MEDIA_FULLY_CACHED
                val mediaId = extras?.getString(METADATA_KEY_MEDIA_ID)
                if (mediaId != null) {
                    adapter.updatedCached(mediaId, cached)
                }
            }
                PLAYER_NOT_READY -> {
                    Toast.makeText(context,getString(R.string.player_not_ready),
                            Toast.LENGTH_LONG).show()
                }
            }
        }

    }

    fun scrollToNowPlaying() {
        if (adapter.nowPlaying >= 0)
            folderView.scrollToPosition(adapter.nowPlaying)
    }

    private val subscribeCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) {
            Log.d(LOG_TAG, "Received folder listing ${children.size} items")
            super.onChildrenLoaded(parentId, children)
            var empty = false
            var folderDetail: Bundle? = null
            if (children.size==0) {
                Toast.makeText(this@FolderFragment.context, R.string.empty_folder, Toast.LENGTH_LONG).show()
                empty = true
            } else {
                folderDetail = children[0].description.extras?.getBundle(METADATA_KEY_FOLDER_DETAILS)
            }
            this@FolderFragment.adapter.changeData(children)
            scrollToNowPlaying()
            doneLoading(folderDetail, false, empty)
        }

        override fun onError(parentId: String) {
            super.onError(parentId)
            Log.e(LOG_TAG, "Error loading folder ${parentId}")
            Toast.makeText(this@FolderFragment.context, R.string.media_browser_error, Toast.LENGTH_LONG).show()
            doneLoading(null, true, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            folderId = it.getString(ARG_FOLDER_PATH)
            folderName = it.getString(ARG_FOLDER_NAME)
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_folder, container, false)
        folderView = view.findViewById(R.id.folderView)
        folderView.layoutManager = LinearLayoutManager(context)

        adapter = FolderAdapter(context!!) {item, action ->
            mediaActivity?.onItemClicked(item, action)
        }
        folderView.adapter = adapter

        if (context is MediaActivity && context is TopActivity) {
            mediaActivity = context as MediaActivity
            (context as TopActivity).setFolderTitle(folderName)
        } else {
            throw RuntimeException(context.toString() + " must implement MediaActivity, TopActivity")
        }

        loadingProgress = view.findViewById(R.id.progressBar)
        return view
    }




    override fun onDestroyView() {
        Log.d(LOG_TAG, "OnDestroyView")
        super.onDestroyView()
        mediaActivity = null
        // save folderView scrolling state for immediate back
        folderViewState = getFolderViewState()
    }

    //TODO - consider additional caching here to minimize network need
    private fun startLoading() {
        mediaActivity?.mediaBrowser?.subscribe(folderId, subscribeCallback)
        loadingProgress.visibility = View.VISIBLE
        folderView.visibility = View.INVISIBLE
    }

    private fun doneLoading(folderDetails: Bundle?, error: Boolean = false, empty: Boolean = false) {
        loadingProgress.visibility = View.INVISIBLE
        folderView.visibility = View.VISIBLE

        if (!error && folderViewState!= null) {
            folderView.getLayoutManager().onRestoreInstanceState(folderViewState)
            folderViewState = null
        }

        mediaActivity?.onFolderLoaded(folderId, folderDetails, error, empty)
    }

    private var folderViewState: Parcelable? = null

    override fun onSaveInstanceState(outState: Bundle) {
        Log.d(LOG_TAG, "onSaveInstanceState")
        super.onSaveInstanceState(outState)
        //fragment is going to be destroyed - save state
        Log.d(LOG_TAG, "Have folderViewState")
        outState.putParcelable(FOLDER_VIEW_STATE_KEY, getFolderViewState())

    }

    private fun getFolderViewState(): Parcelable =
        folderView.getLayoutManager().onSaveInstanceState()


    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // This is not just return from backstack - we're recreating instance so should restore state
        if (folderViewState == null) {
            folderViewState = savedInstanceState?.getParcelable(FOLDER_VIEW_STATE_KEY)
        }
    }


    private var listenersConnected = false
    override fun onMediaServiceConnected() {
        super.onMediaServiceConnected()
        Log.d(LOG_TAG, "onMediaServiceConnect ${mediaActivity?.mediaBrowser}")
        if (mediaActivity?.mediaBrowser != null && ! listenersConnected) {
            startLoading()
            listenersConnected = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (listenersConnected) {
            mediaActivity?.mediaBrowser?.unsubscribe(folderId, subscribeCallback)
            listenersConnected = false
        }
    }


    fun reload() {
        startLoading()
        mediaActivity?.mediaBrowser?.unsubscribe(folderId, subscribeCallback)
        mediaActivity?.mediaBrowser?.subscribe(folderId, subscribeCallback)
    }


    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         */
        @JvmStatic
        fun newInstance(folderPath: String, folderName: String) =
                FolderFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_FOLDER_NAME, folderName)
                        putString(ARG_FOLDER_PATH, folderPath)

                    }
                }
    }
}
