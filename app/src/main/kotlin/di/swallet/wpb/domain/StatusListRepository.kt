package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface StatusListRepository : JpaRepository<StatusList, String>