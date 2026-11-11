package lt.eteris.radio

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

class StationAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Int) -> Unit,
    private val onFavorite: (Station) -> Unit,
) : RecyclerView.Adapter<StationAdapter.Holder>() {

    private var items: List<Station> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var currentId: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submit(items: List<Station>, favorites: Set<String>, currentId: String?) {
        this.items = items
        this.favorites = favorites
        this.currentId = currentId
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setCurrent(id: String?) {
        if (id == currentId) return
        currentId = id
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val initials: TextView = view.findViewById(R.id.initials)
        val name: TextView = view.findViewById(R.id.name)
        val quality: TextView = view.findViewById(R.id.quality)
        val fav: ImageButton = view.findViewById(R.id.fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val st = items[position]
        val ctx = holder.itemView.context
        holder.name.text = st.name
        holder.quality.text = st.qualityLabel
        holder.quality.visibility = if (st.qualityLabel.isEmpty()) View.GONE else View.VISIBLE
        Logos.bind(scope, st.favicon, holder.logo, holder.initials, st.name)

        holder.itemView.setBackgroundResource(
            if (st.id == currentId) R.drawable.bg_card_selected else R.drawable.bg_card,
        )
        val isFav = st.id in favorites
        holder.fav.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (isFav) R.color.accent else R.color.line),
        )
        holder.fav.contentDescription = ctx.getString(if (isFav) R.string.fav_remove else R.string.fav_add)

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        holder.fav.setOnClickListener { onFavorite(st) }
    }
}
